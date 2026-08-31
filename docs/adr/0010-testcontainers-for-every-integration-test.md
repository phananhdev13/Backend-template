# ADR-0010 — Testcontainers for every integration test

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

An integration test's value is proportional to how closely its dependencies resemble
production. Substitutes erode that in ways that are invisible until they are not.

H2 in Postgres compatibility mode is the classic example. It accepts most of the SQL, and
then differs on the parts that matter: `ON CONFLICT` semantics, partial and expression
indexes, `jsonb` operators, `SELECT … FOR UPDATE SKIP LOCKED`, sequence behaviour under
concurrency, and the isolation level actually delivered. Every one of those is something a
service depends on and a test cannot check. The Flyway migrations that pass against H2 are
not the migrations that will run in production, so the one artifact most worth testing is the
one least tested.

Embedded Kafka has the same shape at a different scale: it is a different implementation of
the broker, and consumer rebalancing, transaction behaviour and topic configuration —
precisely what `@EventContract` provisions (ADR-0007) — are where it diverges.

Boot 4.1 gives Testcontainers first-class support: `spring-boot-testcontainers` provides
`@ServiceConnection`, which derives `spring.datasource.*` and `spring.kafka.*` from a running
container, so no test writes a JDBC URL. Testcontainers **2.0.5** is the current line, and it
renamed every module artifact from `<module>` to `testcontainers-<module>` — so
`org.testcontainers:postgresql` becomes `org.testcontainers:testcontainers-postgresql`.
Examples found online still use the 1.x names and will not resolve; `order-service`'s pom
carries a comment saying so, because this will be looked up more than once.

`spring-boot-dependencies` pins `testcontainers.version` but does not import the BOM, so the
root `pom.xml` imports `testcontainers-bom` explicitly at that same property — otherwise
individual modules have no managed version.

The cost is real: a Docker daemon must be available wherever tests run, including CI, and
container startup dominates the runtime of a small test.

## Decision

**Every test that crosses a process boundary uses a real dependency in a container. No
in-memory substitute for a production dependency appears anywhere in this repository.**

- Postgres via `testcontainers-postgresql`, Kafka via `testcontainers-kafka`, wired with
  `@ServiceConnection` so connection details are never hard-coded.
- Flyway migrations run against that container, in order, on every integration test run. The
  schema under test is the schema that will be deployed.
- Containers are shared across the test suite — a singleton pattern or Testcontainers'
  reusable containers — rather than started per test class. Per-class startup is what makes
  people disable integration tests.
- **Testing at the right level, not everything at the top level.** Domain tests are plain
  JUnit with no Spring context at all, which is only possible because the domain layer has no
  framework dependency (ADR-0003). Application tests use mocks for output ports. Only
  adapter and end-to-end tests get containers. If the container count is growing faster than
  the adapter count, tests are being written at the wrong level.
- Boot 4 removed the conveniences that used to hide this: `@SpringBootTest` no longer
  auto-provides `MockMvc` or `TestRestTemplate`, so a web test declares
  `@AutoConfigureMockMvc` or uses `RestTestClient` explicitly. `@MockBean` and `@SpyBean` are
  gone in favour of `@MockitoBean` and `@MockitoSpyBean`.

## Consequences

**Good** — Migrations, native SQL, `jsonb` columns, locking behaviour and broker
configuration are all exercised as they will run. A test failure means a real defect rather
than a substitute's quirk. Onboarding is `git clone` plus `mvn verify` — no local Postgres to
install, no versions to match, no shared test database to corrupt. Upgrading Postgres is a
one-line image tag change with the whole suite as evidence.

**Bad** — Docker is a hard prerequisite. CI runners need a working daemon or a remote
Testcontainers Cloud endpoint, and restricted environments — some corporate CI, some
Kubernetes-based runners without privileged access — need explicit work. First run pulls
images, so a cold cache is slow enough to be mistaken for a hang. The suite is heavier than
one built on H2, and that is a permanent tax on every commit.

**Neutral** — Test execution time becomes a thing to manage rather than to ignore: container
reuse, parallel classes and honest test-level discipline are now part of the build's design
rather than optimisations.

## Alternatives considered

### H2 or HSQLDB in compatibility mode

Fast, dependency-free, and it makes `mvn test` work on a laptop with nothing installed —
genuinely valuable properties, and the reason this is still the most common choice. It lost on
the specific claim it makes and cannot keep: "compatibility mode" means the dialect, not the
engine. The failures it lets through are concentrated in exactly the code worth testing —
migrations, upsert semantics, locking, JSON operators — and those failures surface in
production, where they are most expensive.

### An embedded Postgres binary (zonky, `otj-pg-embedded`)

A real Postgres, so the fidelity argument mostly holds, and no Docker required — which
answers this ADR's main cost. It lost on operational fit: the binary distributions lag
Postgres releases, platform coverage (particularly Apple silicon and Alpine-based CI images)
has been intermittent, and there is no equivalent for Kafka, so a second mechanism would be
needed anyway. One mechanism for every dependency is worth more than avoiding Docker for one
of them.

### A shared integration environment that CI tests against

Highest fidelity of all — it is production's sibling. Rejected because it serialises the
team: concurrent runs interfere, test data accumulates, one bad migration blocks everyone, and
a failure cannot be reproduced locally. It also makes tests non-hermetic, which means a red
build no longer reliably indicates a code defect.

### Mock the adapters and skip integration tests entirely

Fastest, and defensible for a service whose adapters are thin. It lost because the adapters
are where this architecture concentrates its risk: the domain is pure and unit-testable
precisely so that the interesting failures are pushed outward into the adapter layer. Not
testing that layer against a real dependency leaves the risk where it was and removes the
evidence.

## Revisit when

CI cannot provide a container runtime — a platform migration to a restricted runner — at
which point Testcontainers Cloud is the substitute, not H2. Also revisit when a full
integration run exceeds roughly ten minutes on a warm cache: the answer then is container
reuse and moving tests down the pyramid, and this ADR should be amended to say which, rather
than left as the reason nobody wants to run the suite.
