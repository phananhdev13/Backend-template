# P-020 — Aggregates are consistency boundaries

| | |
|---|---|
| **Layer** | domain |
| **Enforced by** | `AggregateRules.oneRepositoryPerAggregateRoot()`, `AggregateRules.aggregatesReferenceOtherAggregatesByIdentityOnly()`, `UseCaseRules.oneAggregateChangedPerTransaction()` (not implemented) in `libs/arch-test` |
| **Annotations** | `@AggregateRoot`, `@DomainEntity`, `@OutputPort` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

An aggregate root owns every invariant inside its boundary and is the only member of that
cluster anything outside may hold a reference to. One transaction changes one aggregate.
Aggregates refer to each other by identity, never by object reference.

## Why

The boundary answers a question every system eventually has to answer under load: *what
must be true at the instant a transaction commits, and what may lag?* Draw it explicitly
and the answer is a design decision. Leave it implicit and the answer is whatever the ORM
happened to fetch.

Draw the boundary too wide and you serialise your hot path. An `Order` aggregate that
contains its `Customer` means every order placement takes a write lock on the customer
row; a customer placing two orders from two tabs deadlocks, and the retry storm shows up
as p99 latency long before anybody suspects the domain model. Optimistic locking makes
this worse, not better: the version column on the wide aggregate now conflicts on writes
that touch entirely unrelated fields.

Draw it too narrow and invariants leak into the use case, where they are enforced by
whoever remembers. `OrderLine` as its own aggregate means "an order's total may not exceed
the customer's credit limit" has no owner. Two concurrent line additions each read a total
below the limit, each pass, and the committed order is over it — a bug that is invisible in
single-threaded tests and reproducible only under concurrency.

Object references across aggregates cause the third failure. A `Order.customer` field of
type `Customer` invites lazy loading, which invites `LazyInitializationException` outside
the transaction, which invites `JOIN FETCH`, which quietly widens the transaction back to
the boundary you were avoiding. Holding `CustomerId` instead makes the second load
explicit, keeps it out of the write transaction, and makes the aggregate serialisable and
testable without a persistence context.

Anything that must span two aggregates is a process, not a transaction: publish an event
and let the other side converge ([P-072](P-072-transactional-outbox.md)). That is a
deliberate choice of eventual consistency, made once and visibly, rather than a distributed
transaction discovered later.

## In code

```java
package com.acme.orders.ordering.domain;

@AggregateRoot
public final class Order {

    private final OrderId id;
    private final CustomerId customerId;        // identity, not a Customer reference
    private final List<OrderLine> lines;        // @DomainEntity, reached only through Order
    private OrderStatus status;
    private long version;

    public static Order place(OrderId id, CustomerId customerId, List<OrderLine> lines, Money creditLimit) {
        Money total = lines.stream().map(OrderLine::subtotal).reduce(Money.zero(), Money::plus);
        if (total.isGreaterThan(creditLimit)) {
            throw new BusinessRuleViolation(
                    "order.exceeds-credit-limit",
                    "Order total %s exceeds the credit limit %s".formatted(total, creditLimit),
                    Map.of("total", total.toString(), "creditLimit", creditLimit.toString()));
        }
        return new Order(id, customerId, List.copyOf(lines), OrderStatus.PLACED);
    }

    public OrderPlaced toEvent(Instant occurredAt) { … }
}
```

The repository exists for the root and returns the whole boundary:

```java
@OutputPort
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    Order save(Order order);                     // whole aggregate, one version check
}
```

Wrong — a repository for an interior entity lets callers change half an aggregate:

```java
@OutputPort
public interface OrderLineRepository {           // fails AggregateRules
    OrderLine save(OrderLine line);               // the credit-limit invariant now has no owner
}
```

## Enforcement

`AggregateRules.oneRepositoryPerAggregateRoot()` matches every `@OutputPort` whose name
ends `Repository` against the aggregate type in its signatures and fails when that type is
not `@AggregateRoot`:

```
com.acme.orders.ordering.application.port.out.OrderLineRepository persists
com.acme.orders.ordering.domain.OrderLine, which is a @DomainEntity, not an @AggregateRoot.
Interior entities are saved through their root.
See docs/principles/P-020-aggregate-consistency-boundaries.md
```

`AggregateRules.aggregatesReferenceOtherAggregatesByIdentityOnly()` fails any field on an
`@AggregateRoot` or `@DomainEntity` whose type is another `@AggregateRoot` (or a collection
of one).

`UseCaseRules.oneAggregateChangedPerTransaction()` (not implemented) inspects `@UseCase` classes and fails
when more than one repository `save`/`delete` is reachable inside a single `@Transactional`
method. It is a heuristic and it can be suppressed with `@Adr`; it catches the common case
where a second aggregate is saved "just to keep them in sync".

## Deviating

Two aggregates in one transaction is defensible when they share a database, the second
write is idempotent, and eventual consistency would be observable to a user in a way the
business rejects. Record it with `@Adr` on the use case, name the lock ordering you rely
on, and say what happens when the second write fails.

An aggregate that must be large — a document, a ledger period — is fine if writes to it are
rare. Say so in the ADR; the objection is contention, not size.

Related: [P-021](P-021-illegal-states-unrepresentable.md) for the invariants inside the
boundary, [P-032](P-032-reads-and-writes-shaped-separately.md) for reading across
boundaries without widening them.
