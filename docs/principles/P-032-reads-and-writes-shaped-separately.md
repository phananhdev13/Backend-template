# P-032 — Reads and writes are shaped separately

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `ReadModelRules.readModelsHaveNoSideEffects()`, `ReadModelRules.readModelsAreReadOnly()`, `ReadModelRules.readModelsDoNotBorrowTheWriteSide()`, `TraceabilityRules.everyReadModelIsDocumented()` in `libs/arch-test` |
| **Annotations** | `@ReadModel`, `@UseCase`, `@OutputPort` |
| **Guide** | [G-040](../guides/G-040-persistence.md) |

## Rule

Queries are `@ReadModel` classes that project straight from storage into a shape the caller
asked for. They never load aggregates, never mutate anything, and never share a type with
the write path. Anything that changes state is a `@UseCase`: if it answers a question it is
a read model, if it takes a decision it is a use case.

## Why

**Aggregates are the wrong shape for reading.** An aggregate is assembled to protect
invariants: it loads every line, every adjustment, every child entity, because a decision
might need any of them. A list screen showing order number, customer name, total and status
needs four columns. Hydrating 200 aggregates to render 200 rows costs several hundred
queries, tens of megabytes of garbage per request, and a p99 that degrades with the size of
the largest order in the page. This is the single most common cause of "the service got slow
and nothing changed" — nothing did change, except that some customer's order grew to 400
lines.

**Sharing the type is worse than sharing the query.** When the list endpoint returns the
same `Order` the write path uses, the read requirements start deforming the aggregate: a
`customerName` field denormalised onto `Order` so the list does not need a join; a
`lineCount` maintained on write because computing it on read was slow. Now the aggregate
carries state that no invariant needs, that can be stale, and that the domain has to keep
consistent for a reason that has nothing to do with the domain.

**Reading through the write path forces transactions where none are needed.** A query that
goes through a repository inherits the write path's transaction semantics and its locking.
Read models can be answered from a replica, from a materialised view, or from a cache
precisely because they change nothing — but only if the system knows they change nothing.
The side-effect-free rule is what buys that freedom, so it is enforced rather than assumed.

**The separation is also how the two evolve at different speeds.** A new dashboard is a new
`@ReadModel` and a new SQL projection: additive, testable in isolation, deletable when the
dashboard is retired. The write model is untouched, so nothing about the new screen can
break order placement.

**Bypassing the domain is a licence, not a default.** A read model may write SQL against the
same tables the aggregate persists to — a coupling that would be unacceptable in the write
path. It is acceptable here because a read model cannot corrupt anything, and because the
alternative (projecting through the domain) reintroduces the performance problem this
principle exists to solve. What it must not do is *reinterpret* a business rule in SQL: a
list of "orders needing approval" whose predicate is a hand-written `WHERE` clause will
diverge from the policy that defines it.

## In code

```java
package com.acme.orders.ordering.application;

@ReadModel(id = "QRY-ORD-004")
public final class OrderSummaryQuery {

    private final JdbcClient jdbc;

    public OrderSummaryQuery(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Projection shaped for the list screen. Not a domain type, and deliberately so. */
    public record OrderSummary(UUID orderId, String customerName, BigDecimal total,
                               String currency, String status, Instant placedAt) {}

    @Transactional(readOnly = true)
    public List<OrderSummary> recentFor(CustomerId customerId, int limit) {
        return jdbc.sql("""
                    SELECT o.id, c.display_name, o.total_amount, o.total_currency, o.status, o.placed_at
                      FROM orders o
                      JOIN customers c ON c.id = o.customer_id
                     WHERE o.customer_id = :customerId
                     ORDER BY o.placed_at DESC
                     LIMIT :limit
                    """)
                .param("customerId", customerId.value())
                .param("limit", limit)
                .query(OrderSummary.class)
                .list();
    }
}
```

Wrong — correct, and quadratic:

```java
@ReadModel(id = "QRY-ORD-004")
public final class OrderSummaryQuery {
    private final OrderRepository orders;          // fails ReadModelRules
    private final CustomerProfiles customers;

    public List<OrderSummary> recentFor(CustomerId customerId, int limit) {
        return orders.findOpenFor(customerId).stream()      // loads every line of every order
                .limit(limit)
                .map(o -> new OrderSummary(o.id().value(),
                        customers.findById(o.customerId()).orElseThrow().name(),  // N+1
                        …))
                .toList();
    }
}
```

Also wrong — a read model that writes:

```java
@Transactional
public List<OrderSummary> recentFor(CustomerId customerId, int limit) {
    auditLog.record(customerId);        // fails ReadModelRules: reads must not have effects
    …
}
```

Audit that access from the inbound adapter, or make it a use case and admit that it changes
state.

## Enforcement

`ReadModelRules.readModelsHaveNoSideEffects()` fails a `@ReadModel` that calls a method whose
name begins with `save`, `delete`, `remove`, `insert`, `update`, `persist`, `merge`, `publish`,
`send`, `store` or `record` on any collaborator:

```
com.acme.orders.ordering.application.OrderSummaryQuery calls AuditLog#record.
A @ReadModel answers questions; it does not change anything. Move the write to the
inbound adapter or promote this to a @UseCase.
See docs/principles/P-032-reads-and-writes-shaped-separately.md
```

`ReadModelRules.readModelsAreReadOnly()` fails a `@ReadModel` with a non-final instance field,
or one carrying `@Transactional` without `readOnly = true` on the class or on any method. The
second is not only tidiness: `readOnly = true` is what lets Hibernate skip dirty checking and a
replica take the query, so omitting it gives up both silently.

`ReadModelRules.readModelsDoNotBorrowTheWriteSide()` fails a `@ReadModel` that depends on a
repository `@OutputPort`, or that returns an `@AggregateRoot` — directly or inside an
`Optional`/`List`. A projection that loads through the write side re-imports the mapping and the
lazy loading the separate read path existed to avoid; one that returns an aggregate makes the
write model the read contract, so every change to it becomes an API change.

`TraceabilityRules.everyReadModelIsDocumented()` resolves `id()` against
`docs/use-cases/QRY-ORD-001-*.md` — the same directory as use cases, because a query is an
operation with a specification like any other — on the same terms as
[P-030](P-030-use-case-unit-of-application-logic.md).

## Deviating

A tiny service with one screen may reasonably read through the repository — the projection
machinery is not free, and premature CQRS costs more than it saves. Keep it as a `@UseCase`
returning a projection record until the query shows up in a latency budget, then split.

Separate read *storage* — a materialised view, a search index, a replica — is a bigger step
with a staleness contract attached. That needs an `@Adr` naming the lag budget and what the
UI does when a just-written record is not yet visible, which is the failure users actually
report ([P-072](P-072-transactional-outbox.md) covers the propagation path).
