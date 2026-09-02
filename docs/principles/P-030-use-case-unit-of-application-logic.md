# P-030 — The use case is the unit of application logic

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `UseCaseRules.useCasesImplementExactlyOneInputPort()`, `UseCaseRules.inputPortsAreInterfaces()`, `UseCaseRules.useCasesCoordinateRatherThanCompute()` (not implemented), `UseCaseRules.useCasesAreTheTransactionBoundary()`, `TraceabilityRules.everyUseCaseIsDocumented()` in `libs/arch-test` |
| **Annotations** | `@UseCase`, `@InputPort` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

One thing the system can be asked to do is one `@UseCase` class implementing exactly one
`@InputPort` with exactly one method. The use case is the transaction boundary, the
authorisation boundary and the unit of application testing. It orchestrates domain objects
through ports; it contains no business rules of its own.

## Why

**The alternative is the service object, and its failure is well documented.**
`OrderService` starts with `place` and `cancel`. It acquires `amend`, `reprice`,
`markShipped`, `retryPayment`, and a constructor with eleven dependencies — of which any one
method uses three. Every test of `place` now needs stubs for the payment gateway and the
notification port that `place` never touches, so tests get slower and more brittle in
proportion to the *class's* growth rather than the *behaviour's*. Merge conflicts
concentrate on the one file every feature edits. And when `amend` needs a widened
transaction, the change lands in a class where `place` also lives, so `place` inherits it
by accident.

**A single method is what makes the boundaries coincide.** Transactions, authorisation,
retries, metrics, tracing and idempotency are all properties of "one thing the user asked
for". When a class has one entry point, `@Transactional` means exactly what it says,
`@PreAuthorize` guards exactly one operation, the timer measures one latency distribution
instead of a bimodal blur of six, and the span name is the use case name. When the class has
six, every one of those becomes an approximation, and the ones expressed as annotations get
copied onto methods inconsistently.

**The identifier is the thread through the system.** `UC-ORD-001` appears in the
specification, in the structured log line, in the trace span, and in the ArchUnit failure
when the specification is missing. An incident that starts with "checkout is slow" becomes
"`UC-ORD-001` p99 rose at 14:02" becomes a document that says what `UC-ORD-001` is supposed
to do — without anybody translating between three vocabularies
([P-060](P-060-observability.md)).

**Coordination, not computation.** A use case that computes has taken logic that belongs on
the aggregate or in a policy ([P-022](P-022-domain-services-and-policies.md)). The tell is a
use case body with arithmetic, comparison against a business threshold, or a `switch` over a
domain enum. That logic is then unreachable from any other entry point, and unreachable in
tests that do not construct the full port set. The use case's job is: load, delegate, save,
publish.

## In code

The port names the operation; the command names its inputs:

```java
package com.acme.orders.ordering.application.port.in;

@InputPort
public interface PlaceOrder {
    OrderId place(PlaceOrderCommand command);
}

public record PlaceOrderCommand(CustomerId customerId, List<RequestedLine> lines, IdempotencyKey key) {
    public record RequestedLine(Sku sku, Quantity quantity) {}
}
```

The use case coordinates and nothing else:

```java
package com.acme.orders.ordering.application;

@UseCase(id = "UC-ORD-001", value = "A customer places an order and receives its identifier")
public final class PlaceOrderUseCase implements PlaceOrder {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderUseCase.class);

    private final OrderRepository orders;
    private final CustomerProfiles customers;      // @OutputPort
    private final DiscountPolicy discounts;        // @DomainPolicy
    private final EventPublisher events;           // @OutputPort

    public PlaceOrderUseCase(OrderRepository orders, CustomerProfiles customers,
                             DiscountPolicy discounts, EventPublisher events) { … }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public OrderId place(PlaceOrderCommand command) {
        CustomerProfile profile = customers.findById(command.customerId())
                .orElseThrow(() -> NotFoundException.of("Customer", command.customerId()));

        List<OrderLine> lines = command.lines().stream()
                .map(l -> OrderLine.of(l.sku(), l.quantity(), profile.tier(), discounts))
                .toList();

        Order order = Order.place(OrderId.newId(), profile.id(), lines, profile.creditLimit());

        orders.save(order);
        events.publish(order.toEvent(Instant.now()));

        log.atInfo().addKeyValue("useCase", "UC-ORD-001")
                    .addKeyValue("orderId", order.id().value())
                    .log("Order placed");
        return order.id();
    }
}
```

Wrong — the rule has migrated out of the domain, and only this entry point enforces it:

```java
@Override
@Transactional
public OrderId place(PlaceOrderCommand command) {
    Money total = …;
    if (total.isGreaterThan(profile.creditLimit())) {          // belongs in Order.place
        throw new BusinessRuleViolation("order.exceeds-credit-limit", "…");
    }
    …
}
```

## Enforcement

`UseCaseRules.useCasesImplementExactlyOneInputPort()` fails a `@UseCase` implementing zero,
two or more `@InputPort` interfaces, and fails an `@InputPort` declaring more than one
method:

```
com.acme.orders.ordering.application.OrderService implements 2 @InputPort interfaces
(PlaceOrder, CancelOrder). One use case, one port, one method — split the class.
See docs/principles/P-030-use-case-unit-of-application-logic.md
```

`UseCaseRules.inputPortsAreInterfaces()` fails an `@InputPort` that is a class rather than an
interface - the same reasoning [P-031](P-031-dependencies-point-inwards.md) applies to output
ports, argued from hexagonal architecture's own terms: a port is a protocol a human driving the
system and an automated test drive symmetrically, and a class in that position is already one
implementation rather than a substitution point.

`UseCaseRules.useCasesCoordinateRatherThanCompute()` (not implemented) fails a `@UseCase` method exceeding a
cyclomatic complexity of 5, or containing a `switch` over a domain enum. Checkstyle's
`MethodLength` (80) and `CyclomaticComplexity` (12) are the general backstop; this rule is
deliberately tighter.

`UseCaseRules.useCasesAreTheTransactionBoundary()` fails when `@Transactional` appears on an
adapter or on a domain class rather than on the use case — on the class or on any of its
methods, since the method is where it is usually written.

One adapter is exempt, deliberately: an `@EventHandler` or `@TaskHandler` may open the
transaction, because the delivery-ledger mark and the use case call have to commit together —
a rebalance between them loses the work, and a crash between them repeats it
([P-071](P-071-idempotency.md)). The handler still may not *do* the work; that is
`AdapterRules.inboundAdaptersOnlyCallInputPorts()`. It brackets, it does not decide.

`TraceabilityRules.everyUseCaseIsDocumented()` resolves `id()` against `docs/use-cases/`
and fails on a dangling identifier ([P-000](P-000-repository-is-the-only-context.md)).

## Deviating

Two operations that are genuinely one atomic request — `confirmAndShip` where confirming
without shipping is never valid — stay one use case with one method. That is not a
deviation; it is naming the unit correctly.

A batch runner that invokes many use cases is an inbound adapter
([P-040](P-040-inbound-adapters-translate.md)), not a use case with a loop. Its transaction
is per item, so a poison record does not roll back the batch.

Genuinely shared orchestration between two use cases goes into a `@DomainService` or a
package-private collaborator that both call — never into a use case that another use case
invokes, which reintroduces nested transactions and doubles the authorisation checks.
