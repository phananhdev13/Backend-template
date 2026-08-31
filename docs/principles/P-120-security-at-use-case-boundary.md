# P-120 — Security decisions happen at the use case boundary

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `SecurityRules.noAuthorisationInAdapters()`, `SecurityRules.domainNeverReadsSecurityContext()` in `libs/arch-test`. Requiring an authorisation annotation on every use case is _review only_ — see **Enforcement** |
| **Annotations** | `@UseCase`, `@InputPort`, `@ReadModel`, `@Adr` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

Authorisation is declared on the `@UseCase` (and `@ReadModel`), not on controllers, not in
the domain. Authentication happens at the edge; the identity it establishes is passed inwards
as data. Every use case declares a rule or an explicit exemption.

## Why

**A check in a controller protects one entry point.** `@PreAuthorize` on
`OrderControllerV1.cancel` is bypassed entirely by the Kafka handler, the scheduled job and
the admin CLI that all reach `CancelOrderUseCase` directly. Each new entry point is a new
opportunity to forget, and forgetting produces no error — just an unauthorised action that
succeeds. This is the most common way an internal API becomes an authorisation hole: the
second entry point was added by someone who did not know the first one carried the check.

**The use case is where the operation is named, so it is where the rule can be stated.**
"Who may cancel an order" is a sentence about `UC-ORD-002`, not about `DELETE
/api/v1/orders/{id}`. When the path changes at v2 the rule follows automatically, and when a
second transport is added it inherits the rule rather than needing a copy.

**The domain must not read the security context.** A `SecurityContextHolder.getContext()`
call inside an aggregate makes the aggregate untestable without a security setup, unusable
from a background job where no authentication exists, and dependent on a `ThreadLocal` that
is empty on a reactive or virtual-thread-hopping path — where it fails not by throwing but by
returning an anonymous principal, so the check silently passes or silently denies. Pass the
identity in as a value object; the domain then treats "who" as ordinary data it can be tested
with.

**Coarse and fine authorisation are different problems and need different homes.** A scope or
role — "does this caller do ordering at all" — is a declarative check on the port. Ownership
— "is this *their* order" — depends on state that has to be loaded, so it belongs inside the
use case after the load, expressed as a domain rule that throws
`ErrorKind.FORBIDDEN`. Trying to express ownership declaratively leads to `@PreAuthorize`
expressions containing bean calls that hit the database, which is logic in a string, untested
and invisible to the debugger.

**Read models need authorisation too, and are the usual leak.** A list endpoint that filters
by a customer id taken from the request rather than from the authenticated principal is a
straightforward IDOR: change the id, read someone else's orders. Because read models bypass
the domain by design ([P-032](P-032-reads-and-writes-shaped-separately.md)), nothing else
will catch it.

**Denials must be distinguishable and must not leak existence.** `ErrorKind.UNAUTHENTICATED`
and `ErrorKind.FORBIDDEN` mean different things to a client
([P-050](P-050-error-handling.md)). Where the existence of a resource is itself sensitive,
return `NOT_FOUND` — deliberately, with a comment saying why, so the next reader does not
"fix" it.

## In code

Coarse-grained on the port; ownership inside, after the load:

```java
@UseCase(id = "UC-ORD-002", value = "A customer cancels an order that has not shipped")
public final class CancelOrderUseCase implements CancelOrder {

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")          // coarse: declarative
    public void cancel(CancelOrderCommand command) {
        Order order = orders.findById(command.orderId())
                .orElseThrow(() -> NotFoundException.of("Order", command.orderId()));

        order.assertOwnedBy(command.actor());                     // fine: needs loaded state
        orders.save(order.cancel(command.reason(), Instant.now()));
    }
}
```

Identity travels as data, so the domain stays testable and transport-agnostic:

```java
public record CancelOrderCommand(OrderId orderId, CancellationReason reason, Actor actor) {}

@ValueObject
public record Actor(CustomerId customerId, Set<Permission> permissions) {}
```

```java
@AggregateRoot
public final class Order {

    void assertOwnedBy(Actor actor) {
        if (!customerId.equals(actor.customerId()) && !actor.permissions().contains(Permission.ORDER_ADMIN)) {
            throw new NotPermitted("order.not-yours",
                    "Order %s does not belong to the caller".formatted(id.value()),
                    Map.of("orderId", id.value().toString()));    // no owner id in details
        }
    }
}

/** Service-local DomainException subtype; the kernel supplies the base and ErrorKind.FORBIDDEN. */
public final class NotPermitted extends DomainException {
    public NotPermitted(String code, String message, Map<String, Object> details) {
        super(ErrorKind.FORBIDDEN, code, message, details);
    }
}
```

The adapter authenticates and translates; it does not decide:

```java
@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/api/v1/orders")
class OrderControllerV1 {

    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        cancelOrder.cancel(new CancelOrderCommand(
                new OrderId(id), CancellationReason.CUSTOMER, Actors.from(jwt)));   // translation only
        return ResponseEntity.noContent().build();
    }
}
```

A read model scopes to the principal, never to a request parameter:

```java
@ReadModel(id = "QRY-ORD-004")
public final class OrderSummaryQuery {

    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Transactional(readOnly = true)
    public List<OrderSummary> recentFor(Actor actor, int limit) {      // actor, not a path variable
        …WHERE o.customer_id = :customerId…
    }
}
```

Wrong — the check is on one transport, and the identity comes from the caller:

```java
@DeleteMapping("/{id}")
@PreAuthorize("hasAuthority('SCOPE_orders:write')")      // the Kafka handler has no such guard
ResponseEntity<Void> cancel(@PathVariable UUID id, @RequestParam UUID customerId) {
    cancelOrder.cancel(new CancelOrderCommand(new OrderId(id), CUSTOMER, new Actor(new CustomerId(customerId), Set.of())));
    return ResponseEntity.noContent().build();           // change customerId, cancel anyone's order
}
```

## Enforcement

**Requiring an annotation on every use case is review-only, and the reason matters.** A rule that
demanded one would be satisfied by `@PreAuthorize("permitAll()")`, which is exactly the mistake it
was meant to catch — and it would force a security dependency on services that have no authenticated
callers at all. The check that survives contact with reality is a human asking, per use case, *who
may do this to which record*. The two rules below cover the parts a machine can settle: that the
decision is not taken in an adapter, and that the domain never reads the caller's identity.

Were such a rule added, it would read:

```
com.acme.orders.ordering.application.CancelOrderUseCase declares no authorisation.
Every use case states who may invoke it, or records an exemption with @Adr.
See docs/principles/P-120-security-at-use-case-boundary.md
```

`SecurityRules.noAuthorisationInAdapters()` fails `@PreAuthorize`, `@Secured` or
`@RolesAllowed` on an `@InboundAdapter` or `@EventHandler` method — the check is one layer in,
where all transports meet.

`SecurityRules.domainNeverReadsSecurityContext()` fails any `Layer.DOMAIN` or
`Layer.APPLICATION` reference to `SecurityContextHolder`, `Authentication` or `Jwt`; identity
arrives as an `Actor` value object.

## Deviating

Genuinely public operations exist — a health check, a public catalogue read. Mark them
`@PreAuthorize("permitAll()")` rather than leaving the annotation off, so "unauthenticated"
is a decision in the diff rather than an omission nobody can date.

Where authorisation depends on a remote policy engine, keep the call in the use case behind
an `@OutputPort`, give it a timeout and a fail-closed default
([P-051](P-051-remote-call-resilience.md)), and record the availability trade-off in an
`@Adr` — a policy service outage that fails open is a breach, and one that fails closed is an
outage. Choose on purpose.
