# P-031 — Dependencies point inwards through ports

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `LayeringRules.dependenciesPointInwards()`, `LayeringRules.domainIsFrameworkFree()`, `PortRules.outputPortsSpeakDomainLanguage()`, `PortRules.everyOutputPortHasAnImplementation()` in `libs/arch-test` |
| **Annotations** | `@OutputPort`, `@InputPort`, `@OutboundAdapter`, `@ArchRole` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

Source dependencies run inwards only: adapters know the application, the application knows
the domain, the domain knows nothing. Whatever the application needs from outside is
declared by the application as an `@OutputPort` in domain vocabulary, and implemented
outwards by an `@OutboundAdapter`.

## Why

**The point is not purity, it is the direction of change.** Technology changes on a
schedule you do not control — a broker migration, a Postgres upgrade, a payment provider
acquired and sunset, a Boot major release that renames starters and moves Jackson from
`com.fasterxml` to `tools.jackson`. When dependencies point inwards, each of those is a
change to a handful of adapter classes. When they point outwards, the same change reaches
the use cases and the domain, because a repository interface that returns a JPA entity has
put Hibernate in the signature of your business logic.

**The concrete cost of getting it backwards** is measured in test time and in coupling. A
use case that imports `JdbcTemplate` cannot be tested without a database, so its tests move
into the Testcontainers suite; the suite grows from 40 seconds to 6 minutes; developers stop
running it locally; regressions are caught in CI, twenty minutes after the push, or not at
all. The layering rule is, in practice, a test-latency rule.

**Ports are owned by the caller, not the implementor.** This inversion is the whole
mechanism, and it is the part that gets subtly wrong. `OrderRepository` belongs to the
application because the application decides what it needs — `findOpenFor(CustomerId)` — not
to persistence, which would have offered `findByStatusIn(List<String>)`. Once the port is
owned by the implementor, its signature is shaped by the technology and the leak is
permanent: a port returning `Page<OrderEntity>` has exported Spring Data's pagination model
and Hibernate's entity lifecycle into every caller, and no amount of adapter code puts them
back.

**Leaks are subtle and cumulative.** A port that throws `SQLException`, returns `Optional`
of a `@Entity`, takes a `Pageable`, mentions `ConsumerRecord`, or returns an HTTP status is
already a leak, and each one adds a reason the technology cannot be replaced. The test is
mechanical: could a second adapter of a completely different kind implement this signature
honestly? `save(Order): Order` — yes, in-memory, JDBC, or a REST call to another service.
`save(OrderEntity): OrderEntity` — no.

**The domain's isolation is what makes it portable.** `Layer.mayDependOn` makes DOMAIN a
fixed point: it may depend only on itself. That is why the same aggregate serves a REST
controller, a Kafka handler and a scheduled job unchanged, and why domain tests run in
milliseconds with no context, no profile and no container.

## In code

The application declares what it needs, in its own words:

```java
package com.acme.orders.ordering.application.port.out;

@OutputPort
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    List<Order> findOpenFor(CustomerId customerId);
    Order save(Order order);
}

@OutputPort
public interface PaymentGateway {
    /** @throws DependencyFailure when the provider is unreachable; never an HTTP exception. */
    PaymentReceipt capture(OrderId orderId, Money amount, IdempotencyKey key);
}
```

The adapter implements it outwards and absorbs the technology:

```java
package com.acme.orders.ordering.adapter.out.persistence;

@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
@Repository
class JdbcOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;
    private final OrderRowMapper mapper;          // @Internal — never crosses the port

    @Override
    public Optional<Order> findById(OrderId id) {
        return jdbc.sql("SELECT … FROM orders WHERE id = :id")
                   .param("id", id.value())
                   .query(mapper)
                   .optional();
    }
}
```

Wrong — the port has been written by the technology:

```java
@OutputPort
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    // Every caller now depends on Spring Data, on OrderEntity's lifecycle,
    // and on Pageable. The domain is no longer replaceable, or unit-testable.
    Page<OrderEntity> findByStatusIn(List<String> statuses, Pageable pageable);
}
```

Wiring stays in `config/`, which is the only layer allowed to see both sides
([P-011](P-011-configuration-is-wiring.md)).

## Enforcement

`LayeringRules.dependenciesPointInwards()` asks `Layer.mayDependOn` rather than restating
the matrix, so the enum is the single source of truth:

```
com.acme.orders.ordering.application.PlaceOrderUseCase (APPLICATION) depends on
com.acme.orders.ordering.adapter.out.persistence.JdbcOrderRepository (ADAPTER).
APPLICATION may depend on DOMAIN, APPLICATION. Depend on the @OutputPort instead.
See docs/principles/P-031-dependencies-point-inwards.md
```

`LayeringRules.domainIsFrameworkFree()` bans `org.springframework..`,
`jakarta.persistence..`, `java.sql..`, `tools.jackson..` and `org.apache.kafka..` from
`Layer.DOMAIN`.

`PortRules.outputPortsSpeakDomainLanguage()` fails an `@OutputPort` whose signatures mention a
type outside the domain, the JDK and the kernel — this is the rule that catches `Pageable`,
`ConsumerRecord`, `ResponseEntity` and `@Entity` types.

`PortRules.everyOutputPortHasAnImplementation()` fails a port with no `@OutboundAdapter` declaring
`port = X.class`, which catches ports orphaned by a deleted adapter.

## Deviating

Some infrastructure types are effectively vocabulary and are allowlisted in
`libs/arch-test`: `java.time`, `java.util`, JSpecify annotations and the kernel itself.

A port that must carry a technology concept — a database cursor for a genuinely streaming
export, a broker offset for a replay tool — is legitimate when the concept has no domain
equivalent. Mark it `@Adr`, keep it in its own port, and never let it into a port that
ordinary use cases call.

Related: [P-041](P-041-outbound-adapters-one-port.md) for the adapter side,
[P-032](P-032-reads-and-writes-shaped-separately.md) for the read path, which is allowed to
bypass this layering under stated conditions.
