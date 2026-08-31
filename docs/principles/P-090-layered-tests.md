# P-090 — Tests are layered to match the architecture

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `checkstyle:IllegalImport` (JUnit 4, `org.junit.jupiter.api.Assertions`); the layering itself is _review only_ — see **Enforcement** |
| **Annotations** | `@UseCase`, `@AggregateRoot`, `@OutboundAdapter`, `@InboundAdapter` |
| **Guide** | [G-080](../guides/G-080-testing.md) |

## Rule

Test each layer with the cheapest mechanism that can reach it: domain with plain JUnit,
application with fake adapters and no Spring context, adapters with a slice test against a
real Testcontainer, and one end-to-end test per critical path. A test that needs a heavier
mechanism than its layer is testing at the wrong level.

## Why

**Test latency determines whether tests are run.** A domain suite that runs in 200 ms is run
on every save. One that takes four minutes because every test boots a Spring context is run
in CI, twenty minutes after the push, by which time the author has moved on and the failure
costs a context switch instead of a keystroke. The layering is not aesthetic — it is the
mechanism that keeps the fast feedback loop fast, and the layering rules
([P-031](P-031-dependencies-point-inwards.md)) are what make it possible: a domain with no
framework imports *can* be tested without one.

**Mocks placed at the wrong depth test the mock.** Mocking `OrderRepository` in a use case
test is correct — the port is your interface, its contract is yours, and the test asserts
coordination. Mocking `JdbcClient` is not: the test now encodes the exact SQL calls made, so
every refactor of a query breaks a test that never verified the query was right in the first
place. That is a test with negative value — it fails on changes that are safe and passes on
changes that are broken. Where the boundary is someone else's technology, use the real thing
in a container.

**In-memory substitutes for databases lie about the things that break.** H2 in Postgres mode
accepts SQL Postgres rejects, has different transaction isolation behaviour, and does not
implement `SELECT … FOR UPDATE SKIP LOCKED` the way the outbox relay
([P-072](P-072-transactional-outbox.md)) depends on. A green H2 suite followed by a
production failure on a `jsonb` operator or an unsupported index type is the standard
outcome. Testcontainers 2.0 makes the real engine cheap enough that there is no argument
left — note the artifact rename in 2.0: `testcontainers-postgresql`, not
`org.testcontainers:postgresql`.

**Fakes beat mocks for ports you own.** An `InMemoryOrderRepository`
([P-041](P-041-outbound-adapters-one-port.md)) is written once, behaves like a repository,
and is shared by every use case test. A pile of `when(...).thenReturn(...)` stanzas is
rewritten in every test and drifts from the real adapter's semantics — particularly around
optimistic locking and "save returns the updated version", where the mock returns whatever
the test author assumed.

**End-to-end tests are for wiring, not coverage.** They are the slowest and flakiest layer,
so they earn their place by covering what nothing else can: that the context starts, that the
serialisation is right, that the security filter chain is attached. Pushing business
assertions into them produces a suite that takes fifteen minutes, fails for environmental
reasons twice a week, and gets quarantined — at which point the wiring it was protecting is
unprotected.

**Boot 4 changed two things that quietly break inherited test code.** `@MockBean` is replaced
by `@MockitoBean`, and `@SpringBootTest` no longer auto-configures `MockMvc` — you must add
`@AutoConfigureMockMvc`. Both fail in ways that read as unrelated (a null bean, a missing
`MockMvc`), so they are worth knowing before debugging them.

## In code

Domain — no framework, microseconds:

```java
class OrderTest {

    @Test
    void rejects_an_order_above_the_credit_limit() {
        var lines = List.of(OrderLine.of(new Sku("ABC"), Quantity.of(10), Money.of("120.00", GBP)));

        assertThatThrownBy(() -> Order.place(OrderId.newId(), aCustomer(), lines, Money.of("500.00", GBP)))
                .isInstanceOf(BusinessRuleViolation.class)
                .extracting(e -> ((BusinessRuleViolation) e).code())
                .isEqualTo("order.exceeds-credit-limit");
    }
}
```

Application — fakes for ports, still no context:

```java
class PlaceOrderUseCaseTest {

    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final PlaceOrder useCase = new PlaceOrderUseCase(orders, new StubCustomerProfiles(), new VolumeDiscountPolicy(Quantity.of(10)), events);

    @Test
    void publishes_order_placed_when_an_order_is_accepted() {
        OrderId id = useCase.place(aCommand());

        assertThat(orders.findById(id)).isPresent();
        assertThat(events.published()).singleElement().isInstanceOf(OrderPlaced.class);
    }
}
```

Adapter — a real Postgres, a real schema, one container per class:

```java
@JdbcTest
@Testcontainers
@Import(JdbcOrderRepository.class)
class JdbcOrderRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired JdbcOrderRepository repository;

    @Test
    void save_rejects_a_stale_version() {
        Order saved = repository.save(anOrder());
        repository.save(saved.cancel(CancellationReason.CUSTOMER, Clock.systemUTC()));

        assertThatThrownBy(() -> repository.save(saved.markShipped()))   // stale version
                .isInstanceOf(ConflictException.class);
    }
}
```

Wiring — one per critical path, Boot 4 idioms:

```java
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc                                   // Boot 4: no longer implicit
@Testcontainers
class PlaceOrderEndToEndTest {

    @MockitoBean PaymentGateway payments;               // Boot 4: replaces @MockBean

    @Test
    void accepts_a_well_formed_order() throws Exception { … }
}
```

Wrong — a use case test that boots the world:

```java
@SpringBootTest                    // 6 seconds, a database, and nothing the fake would not have caught
class PlaceOrderUseCaseTest {
    @Autowired PlaceOrder useCase;
    @MockitoBean JdbcClient jdbc;  // and now the test asserts SQL strings
}
```

## Enforcement

**The test layering is not mechanically enforced, deliberately.** The architecture test imports
production classes only (`ImportOption.DoNotIncludeTests`), and that exclusion is load-bearing: if
test classes were in scope, a production rule could be satisfied by a class that only exists in the
test tree. Checking test structure would mean a second import that includes them, and the cost of
that — two analyses, two sets of rules, and a standing invitation to move a production class into
the test source set to quieten a rule — buys less than it costs at this size. It is tracked as
TD-003 in `docs/exec-plans/tech-debt-tracker.md`, with the trigger that would change the trade.

What the build does hold you to is the part that silently rots a suite: a domain test that starts a
Spring context is visible in review as an annotation on the class, and a slow suite is visible in
CI timings. What review should look for is a domain rule being tested through HTTP, which is the
expensive mistake this principle exists to prevent.

Checkstyle `IllegalImport` bans `junit.framework`, `org.junit.Assert` and
`org.junit.jupiter.api.Assertions` — assertions are AssertJ, so failures read the same
everywhere. JaCoCo enforces the coverage floor; it is a floor, not a target.

## Deviating

A domain type that is genuinely a thin wrapper over a framework construct may need a context
in its test. That usually means it is an adapter wearing the wrong role annotation
([P-010](P-010-annotated-architecture.md)) — check that first.

Contract tests against a third party's sandbox are slow, externally flaky and worth having.
Keep them in a separate profile so they never gate the fast build, and record in an `@Adr`
what happens when the sandbox is down.
