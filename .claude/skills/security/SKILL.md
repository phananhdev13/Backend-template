---
name: security
description: Authentication (Keycloak SSO) and authorisation (OPA, plus role checks) in this repo - where the check belongs, how to wire a service to the platform's identity provider and policy sidecar, handling secrets, and avoiding data leaks through errors and logs. Use when adding an endpoint that is not public, when a use case must be restricted to certain callers or must ask a policy engine, when handling tokens or secrets, or when reviewing a change for security impact.
---

# Security

## The check belongs at the use case, not the controller

A controller-level check protects one entry point. The same use case reached from a message
listener, a scheduled job or another controller is then unprotected, and nothing about the code
says so. See [P-120](../../../docs/principles/P-120-security-at-use-case-boundary.md) and
[ADR-0021](../../../docs/adr/0021-keycloak-sso-and-opa-as-the-standard-authorization-sidecar.md)
for the full reasoning; this page is the how-to.

## Authentication: Keycloak, via `libs/security-support`

Every service behind this platform's SSO depends on `security-support` and configures its own
Keycloak realm:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/acme}
          authority-prefix: "ROLE_"
          # Keycloak puts realm roles under realm_access.roles, not the flat "scope" claim
          # Spring Security's own conventions assume - REQUIRED for @PreAuthorize("hasRole(...)")
          # to see anything at all.
          authorities-claim-expressions:
            - "['realm_access']['roles']"
```

**Get the SpEL expression exactly right - this is the one mistake that fails silently.** The
expression is evaluated against the raw JWT claims map as its root object, not against the `Jwt`
itself: write `['realm_access']['roles']`, never `claims['realm_access']['roles']`. The wrong
form authenticates the caller correctly and grants them **no authorities at all** - no error, no
log line above TRACE, just an empty role set that makes every `@PreAuthorize` deny silently.

Everything else follows automatically once those properties are set:

```java
@UseCase(id = "UC-ORD-004", value = "An operator cancels an order")
@PreAuthorize("hasRole('order-admin')")
public class CancelOrderService implements CancelOrderUseCase { }
```

```java
@InboundAdapter(AdapterKind.REST)
@RestController
class OrderControllerV1 {
    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication) {
        cancelOrder.cancel(new CancelOrderCommand(new OrderId(id), Actors.from(authentication)));
        return ResponseEntity.noContent().build();
    }
}
```

`com.acme.security.Actors.from(authentication)` is the only place a `JwtAuthenticationToken`
becomes a `com.acme.kernel.security.Actor` - the framework-free value every `@Command` carries
inward, per P-120. It reuses Spring's already-resolved `token.getAuthorities()` rather than
re-parsing the raw JWT a second time, and strips the `ROLE_` prefix so domain code reads
`actor.hasRole("order-admin")` with no Spring-specific noise.

Missing or invalid tokens, and `@PreAuthorize` denials, both render as the same RFC 9457 shape
every other failure in the service uses (`SecuritySupportAutoConfiguration`'s
`AuthenticationEntryPoint`/`AccessDeniedHandler` for the first, `AccessDeniedProblemAdvice` for
the second) - never Spring Security's own default body.

## Authorisation against OPA, the platform standard for anything beyond a role

Some decisions are not "does this caller hold this role" but "does this specific request, given
this actor and this resource, satisfy a rule the platform authors centrally and rolls out
without redeploying every service" - that rule lives in OPA, run as a sidecar polling a bundle
service in Kubernetes.

**Call it from inside the use case, behind your own `@OutputPort` - never from `@PreAuthorize`
SpEL.** A bean-calling SpEL expression is untestable without a live OPA, invisible to a
debugger, and has no `@OutputPort` for `ResilienceRules.remoteCallsDeclareTimeouts` to find a
timeout against - the same reason P-120 already rejects it for ownership checks.

```java
@OutputPort
public interface AgentAuthorizationPort {
    boolean canActivateVersion(Actor actor, AgentId agentId);
}

@OutboundAdapter(port = AgentAuthorizationPort.class, kind = AdapterKind.HTTP_CLIENT)
@ImplementsPrinciple(value = {"P-051", "P-120"}, note = "500ms connect / 1s read timeout in "
        + "application.yml; OpaAuthorization fails closed, so an unreachable sidecar denies.")
public class OpaAgentAuthorizationAdapter implements AgentAuthorizationPort {
    private final OpaAuthorization opaAuthorization;

    @Override
    public boolean canActivateVersion(Actor actor, AgentId agentId) {
        return opaAuthorization.check(actor, "activate-agent-version", Map.of("agentId", agentId.value()));
    }
}

@UseCase(id = "UC-AGT-003", value = "A platform engineer activates an agent version")
public class ActivateAgentVersionService implements ActivateAgentVersionUseCase {
    public void activateVersion(ActivateAgentVersionCommand command) {
        AgentId id = new AgentId(command.agentId());
        if (!authorization.canActivateVersion(command.actor(), id)) {
            throw new NotPermitted("agent.activation-not-permitted", "...", Map.of("agentId", id.value()));
        }
        // ... load, mutate, save
    }
}
```

`com.acme.security.opa.OpaAuthorization` (in `security-support`) owns the mechanics: it shapes
`{"input": {"actor": {...}, "action": "...", "resource": {...}}}`, calls the sidecar through a
declarative `OpaClient` (`@HttpExchange`, wired via `@ImportHttpServices` like any other outbound
HTTP dependency per the resilience skill), and **fails closed** - a timeout, a connection
refusal, a non-2xx, or OPA's own `{}` (no `result` key, meaning nothing is loaded at that
decision path) all deny rather than allow. Configure the specific decision endpoint per service:

```yaml
spring:
  http:
    serviceclient:
      opa:
        base-url: ${OPA_BASE_URL:http://localhost:8181/v1/data/acme/authz/allow}
        connect-timeout: 500ms
        read-timeout: 1s
```

Not a generic `/v1/data/{path}` client: a path variable containing `/` is percent-encoded by the
underlying `RestClient` and never reaches OPA as a path segment, so `base-url` names the full,
specific decision endpoint this service calls.

## Testing both for real, never mocked

A mocked `JwtDecoder` or a stubbed HTTP response proves nothing about the actual wire format -
Keycloak's real JWT shape and OPA's real REST contract are exactly the kind of thing that is
easy to get subtly wrong (see the SpEL gotcha above, found only by decompiling real bytecode
against a real token). Use real Testcontainers for both:

```java
@Container
static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                DockerImageName.parse("quay.io/keycloak/keycloak:26.7"))
        .withCommand("start-dev", "--import-realm")
        .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/acme-realm.json"),
                "/opt/keycloak/data/import/acme-realm.json")
        // Keycloak requires the file's name to be exactly "<realm>-realm.json" - a realm
        // named "acme" is refused from any other filename, confirmed against a real container.
        .waitingFor(Wait.forHttp("/realms/acme").forStatusCode(200));

@Container
static final GenericContainer<?> OPA = new GenericContainer<>(
                DockerImageName.parse("openpolicyagent/opa:1.20.1"))
        .withCommand("run", "--server", "--addr", ":8181", "/policy.rego")
        .withCopyFileToContainer(MountableFile.forClasspathResource("opa/acme-authz.rego"), "/policy.rego")
        .waitingFor(Wait.forHttp("/health").forStatusCode(200));
```

Fetch a real client-credentials token from Keycloak (no password flow needed - a service
account's realm roles are enough), point `issuer-uri`/`base-url` at the containers via
`@DynamicPropertySource`, and assert against the real response. See
`KeycloakResourceServerIntegrationTest` and `OpaAuthorizationIntegrationTest` in
`security-support`, and `ActivateAgentVersionAuthorizationIntegrationTest` in `agent-factory` for
the full shape, allow case, deny case, and unauthenticated case together.

A context-loading test that is not itself about security (a caching test, an OpenAPI contract
test) should exclude the security autoconfiguration entirely rather than fake a token - see
`agent-factory`'s `AgentSecurityTestExclusions` for the full, verified exclusion list (Boot 4.1
ships several independent default-`SecurityFilterChain` autoconfigurations for a servlet OAuth2
resource-server app, not one).

## Authorise on data, not only on role

Most real failures are not "could this role do this?" but "could this caller do this **to this
record**?". A permission check that passes for any order lets an authenticated customer read every
other customer's orders by changing an identifier.

Scope the query rather than filtering the result: load by `(orderId, callerId)` so a mismatch
returns nothing. Filtering after loading leaks through timing, through error messages, and through
the next developer who reuses the loader.

## Failures must not distinguish what the caller may not know

Return 404 rather than 403 when the caller is not allowed to know a resource exists - otherwise the
status code becomes an enumeration oracle. Authentication failures should not reveal whether the
account exists.

Use `ErrorKind.UNAUTHENTICATED` and `ErrorKind.FORBIDDEN`; `libs/web-support` maps them and, for
unexpected exceptions, returns a body containing nothing at all. Never let an exception message,
stack frame or SQL fragment reach a client.

## Input

Validate at the edge with Jakarta Validation, and make the domain refuse invalid values anyway - a
value object that cannot hold a bad value removes the check from every path at once
([P-021](../../../docs/principles/P-021-illegal-states-unrepresentable.md)).

Parameterised queries only. Never build SQL or JPQL by concatenation, including for sort columns
and dynamic filters - allowlist those against a fixed set instead.

## Secrets

Never in the repository, never in `application.yml`, never in a log line, never in an exception
message. They arrive as environment variables or from a secret manager. `.gitignore` covers
`.env`, `*.p12` and `*.jks`, and `.claude/settings.json` denies reading them. A Keycloak client
secret and an OPA sidecar's own credentials (if bundle polling is authenticated) are secrets by
the same rule - `KEYCLOAK_ISSUER_URI` and `OPA_BASE_URL` are not sensitive on their own, but a
client secret used against them is.

If a secret is committed, rotating it is the fix. Removing it from history is cleanup, not
remediation - assume it is already copied.

## Dependencies

`mvn verify -Pdeep-analysis` runs the deeper static analysis; dependency vulnerability scanning runs
in CI. A finding in a transitive dependency is fixed by upgrading the direct dependency that pulls
it, not by pinning the transitive one - pinning silently diverges from what the direct dependency
was tested against.

## Before merging anything security-relevant

- [ ] the check is on the use case, and covers the specific record, not only the role
- [ ] a remote-policy-engine check is behind an `@OutputPort`/`@OutboundAdapter`, not
      `@PreAuthorize` SpEL, with a declared timeout and a fail-closed default
- [ ] the tenant or owner identifier comes from the principal, not the request
- [ ] errors reveal nothing about existence the caller may not know
- [ ] no personal data, token or secret in logs or in a problem response
- [ ] new endpoints are authenticated by default; public ones are deliberate and listed
- [ ] tests exercise real Keycloak/OPA containers, not a mocked decoder or a stubbed response
