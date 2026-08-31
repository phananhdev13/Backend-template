# ADR-0006 — Spring Modulith for module boundaries and event publication

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Two problems in a feature-sliced service (ADR-0003) look unrelated and are not.

**Boundaries.** Feature packages only stay separate if something stops one reaching into
another's internals. `@PublicApi` and `@Internal` state the intent; something has to check
it, at a granularity that matches how features actually change.

**Reliable event publication.** A use case that changes state and publishes an event has a
dual-write problem: the commit and the publish are two operations, and a crash between them
loses the event or emits one for a transaction that rolled back. The standard fix is the
transactional outbox — write the event to the same database in the same transaction, and relay
it afterwards. Writing one by hand means a table, a relay, a retry policy, a completion marker,
a startup republisher, and a set of bugs that only appear under partial failure.

Spring Modulith 2.1.1 addresses both, and — verified — depends on `spring-boot-autoconfigure`
**4.1.1**, so unlike Spring Cloud (ADR-0004) it is available on our baseline. Its Event
Publication Registry persists a row per event and listener inside the publishing transaction,
marks it complete when the listener succeeds, and republishes incomplete publications on
restart. `spring-modulith-starter-jpa` puts that registry in the same datastore the aggregate
was written to, which is what makes the write atomic.

One constraint follows from the dependency graph: Modulith 2.1.1 pins **ArchUnit 1.4.2**
transitively, so this repository pins `archunit.version` to 1.4.2 rather than 1.5.0. Two
ArchUnit versions on one test classpath is not a theoretical hazard — the failure is a
confusing `NoSuchMethodError` inside a test run that otherwise looks healthy.

## Decision

Adopt **Spring Modulith 2.1.1** in every service, via `spring-modulith-starter-core`,
`spring-modulith-starter-jpa` and `spring-modulith-starter-test`, imported through
`spring-modulith-bom`.

- **Boundaries.** Each feature package under `com.acme.<service>` is a Modulith application
  module, and `ApplicationModules.verify()` fails when one module references another's
  internals. This runs alongside our ArchUnit rules rather than replacing them: Modulith owns
  *module-to-module* visibility, `libs/arch-test` owns layering, roles and traceability
  (ADR-0005), and both must agree with the `@PublicApi`/`@Internal` declarations.
- **Event publication.** Domain events go through `ApplicationEventPublisher` and are consumed
  with `@ApplicationModuleListener`. The Event Publication Registry is the outbox; nothing here
  writes its own outbox table.
- **ArchUnit is pinned to 1.4.2** in the root `pom.xml`, with a comment saying why. Bumping it
  independently of Modulith is a build change to be justified, not a routine update.

The registry handles in-process, cross-module delivery. Publishing *out* of the service to
Kafka or AMQP is a listener on that registry; ADR-0007 describes what goes on the wire.

## Consequences

**Good** — A supported outbox instead of a maintained one, including the restart republication
path hand-rolled outboxes almost always get wrong. Boundary violations fail a test in the
module that caused them. `ApplicationModules` also generates C4 and PlantUML documentation from
the actual code — the only kind of architecture diagram that stays true.

**Bad** — Our ArchUnit version is now Modulith's to choose: until it moves to 1.5.x, any rule
needing a 1.5 API is unavailable. The registry adds tables to every service's schema and their
Flyway migrations, and incomplete publications accumulate if a listener fails permanently, so
it needs monitoring of its own — an unbounded table nobody looks at is an outage in slow
motion.

**Neutral** — Modulith's notion of a module is a package, matching our feature slices exactly.
A different package layout would make the tool unhelpful — a reason to keep the layout.

## Alternatives considered

### A hand-written transactional outbox

Full control over the table shape, the relay's batching and the retry policy — and no
dependency. It lost on the parts nobody enjoys writing twice: ordering under concurrent relays,
redelivery after a relay crash, republication of incomplete events on startup, and cleanup.
Modulith's registry does these and is maintained by the Spring team.

### Debezium change-data-capture on the outbox table

Genuinely superior at scale: the relay is the database's replication log, so there is no
polling and no relay process to fail. Rejected as the default because it adds Kafka Connect and
a Debezium connector to every environment — a developer's laptop included — turning "run the
tests" into "run the platform". It remains the right answer above the throughput at which
registry polling becomes the bottleneck, and would supersede this ADR.

### ArchUnit 1.5.0 with a Modulith exclusion and manual version management

Would unlock the newest rule APIs. Rejected because the exclusion must be maintained in every
module's test scope, and the failure when someone forgets is a runtime `NoSuchMethod` in a
suite that otherwise looks healthy. No rule this repository needs today requires 1.5.

### Modulith for boundaries only, no event registry

Attractive for services that publish nothing internally. Rejected as a default because teams
that later need reliable publication would each solve it differently — precisely the divergence
a template exists to prevent.

## Revisit when

Spring Modulith publishes a release that depends on ArchUnit 1.5.x — bump both together in
one commit, and delete the pinning comment. Revisit the registry choice when the
`event_publication` table's incomplete-row count or relay latency becomes an operational
concern in any service; that is the threshold at which change-data-capture starts to pay for
its infrastructure.
