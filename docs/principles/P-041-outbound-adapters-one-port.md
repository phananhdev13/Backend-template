# P-041 — Outbound adapters implement exactly one port

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `AdapterRules.outboundAdaptersImplementTheirDeclaredPort()`, `AdapterRules.oneAdapterPerPortPerKind()`, `PortRules.everyOutputPortHasAnImplementation()` in `libs/arch-test` |
| **Annotations** | `@OutboundAdapter`, `@OutputPort`, `@Internal` |
| **Guide** | [G-040](../guides/G-040-persistence.md) |

## Rule

An `@OutboundAdapter` implements the single `@OutputPort` it names in `port()`, using the
single technology it names in `kind()`. Its mapping types, row mappers and generated clients
are `@Internal` and never appear in the port's signature.

## Why

**`port()` is what makes the hexagon navigable in both directions.** From a port you can
find every implementation; from an implementation you can find the contract it is held to.
Without it, finding "what actually talks to Postgres for orders" is a text search, and
finding "is this port still implemented" is impossible — which is how ports outlive their
last adapter and use cases end up injecting an interface that only a test double satisfies.

**One adapter per port is a blast-radius rule.** A `PersistenceAdapter` implementing
`OrderRepository`, `CustomerProfiles` and `InventoryLedger` couples three ports' lifecycles:
a schema change for customers redeploys the class that orders depend on, its test fixture
has to set up three tables to test one, and when inventory moves to a different store the
class has to be dismembered rather than deleted. The reverse — one port implemented by two
adapters of the same kind — is worse: two classes claim the same contract, Spring picks one
by bean name or fails to start, and which one production used is a question nobody can answer
from the code.

**One technology per adapter keeps failure modes separable.** An adapter that reads from
Postgres and writes to Kafka has two independent failure modes, two latency profiles and two
retry policies fused into one class, so the resilience configuration
([P-051](P-051-remote-call-resilience.md)) can only be right for one of them. When the broker
is slow, the metric that moves is the repository's.

**Adapters absorb impedance; they do not export it.** The row mapper, the JPA entity, the
generated OpenAPI client, the `ConsumerRecord` handling — all of it stays inside. The moment
one of those types appears in a port signature, the technology has escaped into the
application ([P-031](P-031-dependencies-point-inwards.md)) and the adapter is no longer
replaceable. `@Internal` states the intent and `BoundaryRules` enforces it.

**Adapters translate failures too.** A `PSQLException` for a unique-constraint violation is
a `ConflictException` with a stable code; a 404 from a payment provider is a
`NotFoundException`; a socket timeout is `ErrorKind.TIMEOUT`, and the caller is told that
whether the work happened is unknown. Letting the raw exception through puts the driver's
exception hierarchy into every catch block in the service.

## In code

```java
package com.acme.orders.ordering.adapter.out.persistence;

@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
@Repository
class JdbcOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;

    @Override
    public Order save(Order order) {
        int updated = jdbc.sql("""
                    UPDATE orders SET status = :status, total_amount = :total, version = version + 1
                     WHERE id = :id AND version = :version
                    """)
                .param("id", order.id().value())
                .param("version", order.version())
                .param("status", order.status().name())
                .param("total", order.total().amount())
                .update();

        if (updated == 0) {
            throw new ConflictException("order.stale-version",
                    "Order %s was modified concurrently".formatted(order.id().value()),
                    Map.of("orderId", order.id().value().toString(), "expectedVersion", order.version()));
        }
        return order.withVersion(order.version() + 1);
    }
}

@Internal
record OrderRow(UUID id, UUID customerId, String status, BigDecimal totalAmount, String totalCurrency, long version) {
    Order toDomain(List<OrderLine> lines) { … }
}
```

A second adapter of a *different* kind is fine and often the point — the test wiring uses it
without a database:

```java
@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.IN_MEMORY)
public final class InMemoryOrderRepository implements OrderRepository { … }
```

Wrong — three ports fused, and the port's own type leaked outwards:

```java
@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
class PersistenceAdapter implements OrderRepository, CustomerProfiles, InventoryLedger {
    public OrderRow findRow(OrderId id) { … }        // @Internal type on a public method
}
```

## Enforcement

`AdapterRules.outboundAdaptersImplementTheirDeclaredPort()` checks three things: the class
implements the interface named in `port()`, that interface is annotated `@OutputPort`, and
the class implements no other `@OutputPort`:

```
com.acme.orders.ordering.adapter.out.persistence.PersistenceAdapter declares
port = OrderRepository.class but also implements CustomerProfiles, InventoryLedger.
One adapter, one port. Split the class.
See docs/principles/P-041-outbound-adapters-one-port.md
```

`AdapterRules.oneAdapterPerPortPerKind()` fails when two `@OutboundAdapter` classes declare
the same `port()` and the same `kind()` — ambiguous wiring caught at build time rather than
at context startup.

`PortRules.everyOutputPortHasAnImplementation()` fails an orphaned port.

`BoundaryRules.internalTypesStayInTheirModule()` ([P-100](P-100-vertical-slice-modules.md))
catches `@Internal` types escaping through a public signature.

## Deviating

A port whose implementation is genuinely two technologies — a repository with a read-through
cache — is one adapter with `kind = PERSISTENCE` that composes a cache collaborator, not two
adapters for one port. `PERSISTENCE` is not remote for `ResilienceRules`, so that adapter's cache
hop is not checked there; its timeout and failure policy come from `caching-support`
([ADR-0022](../adr/0022-distributed-caches-are-bounded-by-the-module-not-the-caller.md)).
Note the composition with `@Adr`, because the caching semantics
(invalidation, staleness) are a decision, not an implementation detail.

Where a port must be satisfied differently per tenant or per region, keep one adapter per
kind and select inside it from a value the caller passes. Selecting between adapter beans in
configuration reintroduces a decision into wiring
([P-011](P-011-configuration-is-wiring.md)).
