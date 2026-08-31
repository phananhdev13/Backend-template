# G-080 — Writing tests

Put each test at the cheapest level that can fail for the reason you care about. The conventions are
in the `testing` skill.

| Level | Covers | Context |
|---|---|---|
| Domain unit | rules, invariants, every failure branch | none |
| Use case | orchestration, ports substituted | none |
| Slice | adapter mapping, status codes, serialisation | `@WebMvcTest`, `@DataJpaTest` |
| Integration | the real path through database and broker | Testcontainers |
| Architecture | the structural rules | ArchUnit |

Business rules go in the domain test, where enumerating them is cheap. Re-testing them through HTTP
buys nothing and costs seconds per case.

## Conventions

- **AssertJ only.** `org.junit.jupiter.api.Assertions` is a banned import and checkstyle fails on it.
- JUnit Jupiter 6, driven by the Boot BOM — do not pin it.
- Name tests as sentences: `placingAnOrderWithNoLinesIsRejected()`.
- Inject a fixed `Clock`. `DomainRules.domainDoesNotReadTheSystemClock` makes this possible by
  keeping ambient time out of the domain in the first place.
- No `Thread.sleep`; use Awaitility.

## Spring Boot 4 changes that break remembered test code

`@MockBean`/`@SpyBean` are removed (`@MockitoBean`/`@MockitoSpyBean`). `@SpringBootTest` no longer
provides MockMvc (`@AutoConfigureMockMvc`) or `TestRestTemplate`. Testcontainers 2 renamed its
module artifacts to `testcontainers-<module>`. AOT ignores `-DskipTests` — use `-Dmaven.test.skip`.

## Prefer hand-written stubs to mocks for ports

Ports speak domain language and have few methods, so implementing one in the test is usually shorter
than configuring a mock — and it removes a category of failure where the mock was set up wrongly and
the test proved nothing. See `PlaceOrderServiceTest`.

## The architecture test

Copy `ArchitectureTest` into each service and change the analysed package. Run it alone while
iterating:

```bash
mvn -pl services/<service> -am test -Dtest=ArchitectureTest
```

## Flakiness

A defect in the test or the code, never a reason to retry. Usual causes here: real wall-clock time,
a shared container or row between tests, asynchronous work asserted without Awaitility, and test
ordering dependence. Never `@Disabled` a test to get a build green — delete it and say why.
