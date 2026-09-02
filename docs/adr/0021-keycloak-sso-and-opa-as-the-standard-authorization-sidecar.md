# ADR-0021 — Keycloak for platform SSO; OPA as the standard authorization sidecar

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-02 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Nothing before this ADR gave a service running from this template a real answer to "who is
calling, and may they do this" - `libs/kernel/security/Actor` and `Actors.from(jwt)` existed
only as an illustrative example inside P-120's own text, never built. Every service was left to
invent authentication for itself, exactly the drift P-120 already warns a controller-level check
produces.

The platform's identity provider is Keycloak, and every service behind it is a single-tenant
OAuth2 resource server - authentication happens once, centrally, and a service only ever
verifies a bearer token, never issues one. Two things had to be verified directly rather than
assumed from documentation, because both are easy to get wrong silently:

**Boot 4.1 deprecated `spring-boot-starter-oauth2-resource-server`** in favour of
`spring-boot-starter-security-oauth2-resource-server` (confirmed by reading the deprecated
starter's own POM description) - both resolve to the same
`spring-boot-starter-security` + `spring-boot-security-oauth2-resource-server` today, but only
the new name has a future.

**Keycloak puts realm roles under a nested `realm_access.roles` claim**, not the flat `scope`
claim Spring Security's own conventions assume. Boot 4.1 ships a genuinely new capability for
this - `spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions`, a list of SpEL
expressions combined through `DelegatingJwtGrantedAuthoritiesConverter` (confirmed by
decompiling `JwtConverterConfiguration` in `spring-boot-security-oauth2-resource-server-4.1.1.jar`)
- that extracts authorities from a nested claim with no hand-written `Converter` bean at all.
Getting this wrong is silent, not loud: decompiling `ExpressionJwtGrantedAuthoritiesConverter`
found that it evaluates its expression against `jwt.getClaims()` - the raw claims map - as the
SpEL root object, not the `Jwt` itself, and swallows any `ExpressionException` at TRACE level,
returning an empty list. The correct expression is therefore `['realm_access']['roles']`, not
`claims['realm_access']['roles']` - the latter authenticates the caller and silently grants them
no authorities at all, discovered only by adding a temporary debug endpoint and decompiling the
real bytecode, never by reading Spring Security's own reference documentation. A second,
independently necessary fix: `.jwt(Customizer.withDefaults())` did not reliably pick up Boot's
autoconfigured `JwtAuthenticationConverter` bean in this environment either - the filter chain
now autowires it explicitly.

All of this was proven, not assumed, against a real `quay.io/keycloak/keycloak:26.7` container
(the real latest stable tag, confirmed via quay.io's own tag API) importing a realm-export JSON
and issuing a real client-credentials token. One further empirical finding from writing that
container's realm export: Keycloak 26.7's `--import-realm` refuses a file whose name does not
match `<realm>-realm.json` exactly - a realm named `acme` must be imported from a file literally
named `acme-realm.json`, confirmed by the container's own startup log
(`File name / realm name mismatch`) when a differently-named file was tried first.

For authorization, the platform standard is Open Policy Agent (OPA) - deployed as a sidecar
pod polling a bundle service in Kubernetes, per the requirement that policy be centrally
authored and rolled out without a service redeploy. The real latest stable image is
`openpolicyagent/opa:1.20.1` (confirmed via Docker Hub's own tag API, sorted by last-updated
rather than assumed from a "latest" tag). OPA's REST Data API contract was verified directly
against a real running container rather than assumed from its own documentation:
`POST /v1/data/<policy-path>` with `{"input": {...}}` answers `{"result": true|false}` when a
rule is loaded at that path, and `{}` - no `result` key at all - when nothing is loaded there;
a client must read a missing key as `null`, not as a parse failure, and treat `null` the same
as `false`.

## Decision

**`libs/security-support` gains a second, independent auto-configuration for OPA
(`OpaAutoConfiguration`), deliberately not merged into the Keycloak one
(`SecuritySupportAutoConfiguration`)**: the two are separate concerns a service can depend on
independently, and merging them would force every service exercising only one to configure
both - confirmed the hard way while writing `OpaAuthorizationIntegrationTest`, which needs no
Keycloak at all, and would have needed one had the two configurations shared a class.

**`OpaAutoConfiguration` wires `OpaClient` - a declarative `@HttpExchange`, imported via
`@ImportHttpServices(group = "opa", ...)`, the same pattern every other outbound HTTP dependency
in this repository already follows per the resilience skill** - and `OpaAuthorization`, a thin
fail-closed wrapper that shapes an `Actor`, an action name and a resource map into OPA's input
contract and turns any failure (a timeout, a connection refusal, a non-2xx, or OPA's own `{}`
no-result answer) into `false`. `spring.http.serviceclient.opa.base-url` names the specific
decision endpoint a service calls - not a generic `/v1/data/{path}` client, because a path
variable containing `/` is percent-encoded by the underlying `RestClient` and never reaches OPA
as a path segment.

**A service calls `OpaAuthorization` from inside its own `@UseCase`, behind its own
`@OutputPort` and `@OutboundAdapter(kind = AdapterKind.HTTP_CLIENT)`** - never from a
`@PreAuthorize` SpEL expression. P-120 already rejected bean-calling SpEL for ownership checks
("logic in a string, untested and invisible to the debugger"); a network call to a policy
sidecar is the same problem and worse, since it also has no `@OutputPort` for
`ResilienceRules.remoteCallsDeclareTimeouts` to find a timeout against and no seam a unit test
can substitute. `services/agent-factory`'s `OpaAgentAuthorizationAdapter` demonstrates the
shape: `AgentAuthorizationPort` names the question ("may this actor activate this agent's
version") in the use case's own words, and the adapter is the only place that knows OPA exists.

**Keycloak authentication and OPA authorization compose, but neither requires the other.** A
service may depend on `security-support` for Keycloak alone (coarse, declarative
`@PreAuthorize("hasRole(...)")` per P-120's existing guidance), for OPA alone behind its own
ports, or both - `agent-factory`'s `ActivateAgentVersionService` demonstrates both together: a
real Keycloak-issued `Actor` is the input OPA's decision is made about.

## Consequences

**Good** — A service gets a real, verified OAuth2 resource server and a real, verified
fail-closed OPA client for the cost of one dependency and the properties naming its own
Keycloak realm and OPA sidecar - not by inventing either mechanism itself. The
`authorities-claim-expressions` root-object bug this ADR documents would otherwise cost every
future consumer of this template the same multi-hour bytecode-decompiling investigation it cost
here. Both mechanisms were proven end to end against real containers - a real Keycloak issuing
a real client-credentials token, a real OPA evaluating a real Rego policy, and
`agent-factory`'s own integration test proving both compose inside one real `@UseCase` - never
assumed to work from either project's documentation.

**Bad** — A service using `security-support`'s Keycloak wiring must configure
`authorities-claim-expressions` (or accept that its `@UseCase`s see no roles at all) - there is
no safe default that works for both Keycloak's nested claim shape and a provider using the flat
`scope` claim, so this module is deliberately Keycloak-shaped, not identity-provider-neutral. A
service that enables Keycloak authentication and does not itself need it for every endpoint
(most of an integration test suite, for instance) must explicitly exclude this module's own
autoconfiguration *and seven of Boot's own* to keep an unauthenticated request from succeeding
rather than failing with a default challenge - confirmed one at a time, never all at once, by
reading each successive `NoSuchBeanDefinitionException`: Boot 4.1 gives a servlet OAuth2
resource-server app no fewer than three independent autoconfiguration classes that each try to
build their own default `SecurityFilterChain` bean (a generic HTTP Basic default, an
OAuth2-resource-server-specific one, and an actuator-management one), plus the
`UserDetailsService` and `HttpSecurity`-supplying machinery each of those needs - where Boot 3
had one. `agent-factory`'s `AgentSecurityTestExclusions` records the full, verified list.

**Neutral** — OPA's own REST Data API `{}` no-result response is not distinguishable from "the
input was malformed" without checking the loaded policy path separately; this repository treats
both the same (fail closed), which is the conservative and, per P-120's own words, the only
deliberate choice available.

## Alternatives considered

### `@PreAuthorize` calling an OPA-backed Spring bean directly

`@PreAuthorize("@opa.check('activate-agent-version', #command)")` needs no new port or adapter
and reads compactly at the method. Rejected for the same reason P-120 already rejects a
database-calling SpEL expression for ownership checks: a network call inside an annotation
string is untestable without a live OPA, invisible to a debugger, and has no `@OutputPort` for
`ResilienceRules.remoteCallsDeclareTimeouts` to find a timeout declaration against.

### Keycloak's own Authorization Services (UMA 2.0) instead of OPA

Keycloak ships a policy engine of its own, avoiding a second moving part. Rejected per this
platform's own explicit direction: OPA is the standard authorization mechanism across every
service, independent of which identity provider issues the token, and coupling authorization
decisions to the specific IdP would make swapping either one a rewrite of the other.

### An embedded Rego evaluator instead of a sidecar

A library evaluating Rego in-process (OPA compiled to WASM, or a JVM Rego interpreter) removes
the network hop and its failure mode entirely. Rejected as this platform's default: the
explicit requirement is a sidecar pod polling a bundle service, so that a policy change rolls
out across every service without a redeploy - an embedded evaluator would need its own
bundle-refresh mechanism per service, rebuilding what OPA's own sidecar already does once.

### One combined `SecurityAutoConfiguration` for both Keycloak and OPA

Fewer classes, one dependency line already covers everything a "security" module could mean.
Rejected because it forces a service (or a test) that wants only one of the two concerns to
configure both anyway - confirmed directly while writing `OpaAuthorizationIntegrationTest`,
which exercises OPA alone and needs no live Keycloak.

## Revisit when

Spring Boot ships first-class OPA integration (an `@ImportHttpServices`-equivalent specifically
for policy sidecars, or a Boot-managed `PolicyDecisionClient`), which would remove the reason to
hand-roll `OpaClient`; Keycloak or Spring Security changes how nested claims map to authorities
(re-verify `ExpressionJwtGrantedAuthoritiesConverter`'s SpEL root object against this ADR's own
recorded evidence, the same discipline ADR-0019 and ADR-0020 already apply to their own
decompiled internals); or OPA ships a major version changing its REST Data API's response shape.
