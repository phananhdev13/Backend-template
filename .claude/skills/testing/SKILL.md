---
name: testing
description: Write tests in this repo - which level a test belongs at, JUnit 6 and AssertJ conventions, Testcontainers 2 setup, slice tests for adapters, and the ArchUnit architecture test every service carries. Use when adding or fixing tests, when a @SpringBootTest will not wire, when a test is slow or flaky, or when deciding what to cover where.
---

# Testing

Put each test at the cheapest level that can actually fail for the reason you care about. Most
value comes from the two levels that need no Spring context.

| Level | Covers | Context | Aim for |
|---|---|---|---|
| Domain unit | rules, invariants, every failure branch | none | the bulk |
| Use case | orchestration, with ports stubbed | none | one per use case, plus failures |
| Slice | adapter mapping, status codes, serialisation | `@WebMvcTest`, `@DataJpaTest` | one per adapter |
| Integration | the real path through database and broker | Testcontainers | the few that matter |
| Architecture | the structural rules | ArchUnit | one per service |

Business rules belong in the domain test where enumerating them is cheap. Re-testing them through
HTTP buys nothing and costs seconds per case.

## Conventions

- **AssertJ only.** `org.junit.jupiter.api.Assertions` is a banned import and checkstyle fails on
  it. `assertThat(x).isEqualTo(y)`, `assertThatThrownBy(...)`.
- JUnit **Jupiter 6.0.3** — driven by the Boot BOM, do not pin it.
- Name tests as sentences: `placingAnOrderWithNoLinesIsRejected()`. The `MethodName` check is
  suppressed under `src/test/java` for exactly this reason.
- Inject a fixed `Clock` rather than calling `Instant.now()`. Time-dependent tests fail at
  midnight, in another timezone, or on a slow runner.
- No `Thread.sleep`. Use Awaitility for asynchronous assertions.

## Spring Boot 4 changes that break remembered test code

- `@MockBean` / `@SpyBean` are **removed** → `@MockitoBean` / `@MockitoSpyBean`.
- `@SpringBootTest` no longer provides **MockMvc** → add `@AutoConfigureMockMvc`.
- It no longer provides `TestRestTemplate` / `WebClient` → `@AutoConfigureTestRestTemplate` or
  `@AutoConfigureRestTestClient`.
- AOT ignores `-DskipTests`; use `-Dmaven.test.skip`.

## Testcontainers 2

Module artifacts were renamed in 2.x: `testcontainers-postgresql`, `testcontainers-junit-jupiter`,
`testcontainers-kafka`. The 1.x names do not resolve, and most examples online still use them.

Prefer `@ServiceConnection` over hand-wiring properties:

```java
@SpringBootTest
@Testcontainers
class PlaceOrderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
}
```

Make the container `static` so it is started once per class rather than per test method, and share
one definition across a service's integration tests rather than starting a database per test class.

## The architecture test

Every service carries one, and it is what makes the annotations mean anything:

```java
@AnalyzeClasses(packages = "com.acme.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest static final ArchTests roles = ArchTests.in(RoleRules.class);
    @ArchTest static final ArchTests layering = ArchTests.in(LayeringRules.class);
    @ArchTest static final ArchTests contracts = ArchTests.in(EventContractRules.class);
    // …
}
```

Run it alone while iterating:

```bash
mvn -pl services/order-service -am test -Dtest=ArchitectureTest
```

A failure names the principle document that explains it. Read that before changing the rule.

## When a test is flaky

Flakiness is a defect in the test or the code, never a reason to retry. The usual causes here, in
order: real wall-clock time instead of an injected `Clock`; a shared container or database row
between tests; asynchronous work asserted without Awaitility; and test ordering dependence. Fix the
cause. Never `@Disabled` a test to get a build green - if it must go, delete it and say why in the
commit.
