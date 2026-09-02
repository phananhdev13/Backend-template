---
name: use-case
description: Add or change a use case end to end in a Spring Boot service here - specification, input port, use case class, domain change, output ports, adapters, tests, migration. Use for any request phrased as "let users do X", "add an endpoint that", "the system should when", or when changing what an existing operation does. This is the default workflow for feature work in this repo.
---

# Adding a use case

A use case is one thing a caller can ask the service to do. It is the transaction boundary, the
authorisation boundary, and the unit a test exercises. Everything below exists to keep those three
lined up.

Work outside-in: decide what the caller gets, then what the domain must guarantee, then what the
use case needs from the world, then wire it.

## 0. Write the specification first

`docs/use-cases/UC-<CONTEXT>-<NNN>-<slug>.md`. The build fails if a `@UseCase(id = …)` points at
a file that does not exist, so this is not optional paperwork - it is the thing the identifier
resolves to.

Keep it to what the code cannot say: the actor, the trigger, the preconditions, the rules that
must hold, what the caller observes on success, and each failure the caller must be able to tell
apart. Failures are the part worth arguing about; the happy path writes itself.

## 1. Input port — what the caller may ask

`application/port/in/PlaceOrderUseCase.java`

```java
@InputPort
public interface PlaceOrderUseCase {
    OrderId placeOrder(PlaceOrderCommand command);
}
```

One method. Two methods are two use cases, and `UseCaseRules.inputPortsDeclareASingleOperation`
will say so. Always an interface, never a class - `UseCaseRules.inputPortsAreInterfaces` fails a
port that is not one, because a port that is already one implementation is not a substitution
point a test double can stand beside. The command is a record in the same package, carrying
domain types - `Money`, not `BigDecimal`; `CustomerId`, not `String`. Validation that the type
system can do, the type system should do.

## 2. Domain — the rules that must hold

Put the decision in the object that owns the data. The test for this: if the rule lived somewhere
else, could a second caller reach the same data and skip it? If yes, it is in the wrong place.

```java
@AggregateRoot
public final class Order {
    public static Order place(OrderId id, CustomerId customer, List<OrderLine> lines, Clock clock) {
        if (lines.isEmpty()) {
            throw new BusinessRuleViolation("order.empty", "An order needs at least one line");
        }
        return new Order(id, customer, lines, OrderStatus.PLACED, Instant.now(clock));
    }
}
```

No Spring, no JPA, no serialiser - the domain is `new`-able in a plain unit test, and
`LayeringRules.domainIsFrameworkFree` keeps it that way. Take a `java.time.Clock` rather than
calling `Instant.now()`, so time is a parameter and not a reason a test is flaky.

A public method on an `@AggregateRoot`, `@DomainEntity`, `@DomainService` or `@DomainPolicy` never
takes a bare `String`, `BigDecimal`, `UUID` or primitive number -
`NamingRules.domainSignaturesUseValueObjects` fails it. Wrap it in a `@ValueObject` even if
nothing validates yet; the type is what stops a transposed argument from compiling, not the
validation inside it.

Failures are `DomainException` subclasses with a stable code. Never an HTTP status: the same use
case is reachable from a message listener where a 404 means nothing.

## 3. Output ports — what the use case needs from the world

`application/port/out/`, declared in the language of the application, not of the technology:

```java
@OutputPort
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
}
```

A port that mentions a `ResultSet`, a `ConsumerRecord` or an HTTP status has leaked its
implementation and has stopped being substitutable.

## 4. The use case — orchestration, no rules

```java
@UseCase(id = "UC-ORD-001", value = "A customer places an order")
@Transactional
public class PlaceOrderService implements PlaceOrderUseCase {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public OrderId placeOrder(PlaceOrderCommand command) {
        Order order = Order.place(OrderId.newId(), command.customerId(), command.lines(), clock);
        orders.save(order);
        events.publishEvent(new OrderPlaced(order.id().value(), order.total(), Instant.now(clock)));
        return order.id();
    }
}
```

Load, decide, save, announce. If this class computes rather than coordinates, the calculation
belongs in the domain.

`@Transactional` goes **here** and nowhere below - one use case, one transaction. Publishing
through `ApplicationEventPublisher` rather than straight to a broker is what makes the state change
and its event commit together; see [P-072](../../../docs/principles/P-072-transactional-outbox.md) and the
`events` skill.

One aggregate's repository saved or deleted per use case -
`UseCaseRules.oneAggregateChangedPerTransaction` fails a class whose calls reach `save`/`delete`
on two different aggregates' repositories. A second aggregate that needs to change too is a
reaction to the event this one just published, not a second write in the same commit.

`@UseCase` carries no Spring meaning, so the service's `@ComponentScan` include filter is what
makes these beans. That is deliberate: the application layer stays framework-free.

## 5. Adapters

Inbound (`adapter/in/web/`) translates and nothing more - parse, map to the command, call the
port, map the result. A decision taken here is invisible to every other entry point.

```java
@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/orders")
public class OrderController {
    private final PlaceOrderUseCase placeOrder;   // the port, never PlaceOrderService
}
```

Request and response records live beside the controller and are named `…Request` / `…Response`.
They are not the domain model and must never appear outside an adapter package -
`NamingRules.edgeDataTypesStayInAdapters` enforces that.

Outbound (`adapter/out/persistence/`) implements the port:

```java
@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
public class JpaOrderRepositoryAdapter implements OrderRepository { }
```

Keep the JPA entity separate from the aggregate and map between them. They change for different
reasons: one for business rules, one for query plans and column types.

## 6. Migration

`services/<service>/src/main/resources/db/migration/V<n>__<description>.sql`. Additive only -
expand, migrate, contract. See the `persistence` skill.

## 7. Tests

| Level | Covers | Cost |
|---|---|---|
| Domain unit test | every rule and every failure branch | plain JUnit, no context |
| Use case test | orchestration, with ports stubbed | no context |
| Adapter test | mapping and status codes | slice test |
| Integration test | the path through a real database and broker | Testcontainers |

Rules go in the domain test, where they are cheap to enumerate. Do not re-test them through HTTP;
that buys nothing and costs seconds per case. See the `testing` skill.

## Before you call it done

- [ ] `docs/use-cases/UC-…md` exists and the `@UseCase(id)` matches it
- [ ] every class carries its role annotation
- [ ] the domain compiles without Spring on the classpath
- [ ] each failure the specification distinguishes has its own `DomainException` code
- [ ] domain method signatures take value objects, never a bare `String`/`BigDecimal`/`UUID`/number
- [ ] `@Transactional` on the use case only, and it saves or deletes at most one aggregate's repository
- [ ] published events declare an `@EventContract` and have a schema in `contracts/events/`
- [ ] `mvn -pl services/<service> -am verify` is green, architecture tests included
