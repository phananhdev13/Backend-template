# ADR-0020 — ShedLock and Quartz's clustered JobStore for distributed cron; Temporal's native cron when a service already runs Temporal

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-02 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Every service this template ships runs more than one instance in production. Nothing in the
repository before this ADR gave a `@Scheduled` method any idea another instance existed - a
plain Spring `@Scheduled(cron = ...)` method fires on every instance, on every tick. For a
job that only reads, that is wasted work; for a job that writes (a purge, a report, a
resubmission), it is silent double-processing that becomes an incident the day someone
finally reads the numbers.

Three ways to make a scheduled job safe under more than one running instance, each verified
directly rather than assumed from documentation:

**ShedLock** (`net.javacrumbs.shedlock`, latest `7.9.0`, verified against Maven Central) wraps
a plain `@Scheduled` method with `@SchedulerLock(name, lockAtMostFor, lockAtLeastFor)`.
Decompiling `shedlock-provider-jdbc-template-7.9.0.jar` confirms `JdbcTemplateLockProvider`
is a real, minimal mechanism: one table (`shedlock`: `name`, `lock_until`, `locked_at`,
`locked_by`), no bundled schema-generation tooling of its own (the DDL is written by hand,
same as every other table this repository owns via Flyway), and a best-effort guarantee - a
node holding the lock that dies mid-job releases it only when `lockAtMostFor` elapses, not
instantly. `@EnableSchedulerLock`'s `defaultLockAtMostFor` has no default value in the real
annotation (confirmed via `javap -v`'s `AnnotationDefault` table) - every consumer must set
one explicitly, a safety ceiling this ADR sets to `PT5M` for `ShedLockAutoConfiguration`'s
own default, overridable per job.

**Quartz's clustered JDBC `JobStore`** (`org.quartz-scheduler:quartz`, latest `2.5.2` -
verified to be exactly the version Spring Boot 4.1's own `spring-boot-dependencies` BOM
manages, so `spring-boot-starter-quartz` needs no separate version pin at all) is a real,
persisted scheduler, not merely a lock. Decompiling `quartz-2.5.2.jar` confirms the
mechanism: `StdRowLockSemaphore.SELECT_FOR_LOCK` is the literal string
`SELECT * FROM {0}LOCKS WHERE SCHED_NAME = {1} AND LOCK_NAME = ? FOR UPDATE` - a genuine
pessimistic row lock on `QRTZ_LOCKS`, backed by a `ClusterManager` background thread that
checks in periodically (`org.quartz.jobStore.clusterCheckinInterval`) and recovers a dead
node's `QRTZ_FIRED_TRIGGERS` entries rather than waiting out a timeout. The schema itself -
eleven tables, `tables_postgres.sql`, bundled in the jar - was copied verbatim into this
repository's own Flyway migration rather than regenerated, so it can never silently drift
from what `JobStoreTX`'s own SQL expects.

**Temporal's native cron** (`WorkflowOptions.Builder.setCronSchedule(String)`, confirmed
present in `temporal-sdk` 1.38.0 - already a dependency wherever `temporal-support` is, per
ADR-0016) needs neither of the above. Temporal's server is already the single coordinator
for workflow execution; "exactly one execution per tick across a cluster" is a property of
starting a workflow with a cron schedule, at zero additional infrastructure cost, for a
service that already pays for a Temporal cluster.

The three are not equivalent, and the difference that matters is what a node dying
mid-execution costs:

- ShedLock: the lock survives until `lockAtMostFor` elapses, then the next tick's instance
  proceeds. Simplest, cheapest, and the right trade for an idempotent job where a delayed
  retry is acceptable.
- Quartz: the cluster's `ClusterManager` detects the dead node's stale checkin and explicitly
  recovers its fired-but-unfinished triggers - no waiting out a fixed timeout. The right
  trade for a job whose absence for `lockAtMostFor`'s duration is itself a problem, or that
  needs Quartz-specific features this repository does not otherwise build: misfire policies,
  blackout calendars, a persisted job that outlives a full redeploy of the trigger
  definitions.
- Temporal cron: the workflow's own history and retry policy apply, the same durability
  guarantee P-033 already gives every other workflow. The right choice whenever the
  scheduled work already is, or should be, a workflow - never adopted only to get its cron
  feature.

## Decision

**A `@InboundAdapter(AdapterKind.SCHEDULER)` class is safe to run on more than one instance
by construction, never by convention**, enforced by
`SchedulingRules.schedulerAdaptersAreClusterSafe` in `libs/arch-test`: every such class must
carry a real `@SchedulerLock` method (ShedLock), implement `org.quartz.Job` (Quartz's
clustered `JobStore`), or justify another mechanism with `@Adr`.

**`libs/scheduling-support` supplies the first two mechanisms, both optional and
independently addable**: `ShedLockAutoConfiguration` wires a `JdbcTemplateLockProvider` from
whatever `DataSource` the service already has and turns on `@EnableScheduling`;
`QuartzClusteringAutoConfiguration` supplies the `spring.quartz.*` defaults
(`job-store-type=jdbc`, `isClustered=true`, `instanceId=AUTO`, the Postgres driver delegate)
that turn Boot's own `QuartzAutoConfiguration` from an in-memory single-node scheduler into a
clustered one, leaving Boot to build the actual `Scheduler` bean. Each mechanism's schema
ships as its own Flyway migration, at its own reserved version (`V002` ShedLock, `V003`
Quartz, alongside `messaging-support`'s `V001`) so a service adds only the classpath location
for the mechanism it actually uses.

**A service that already depends on `temporal-support` reaches for Temporal's own cron
schedule instead of either mechanism here**, for any job that already is, or should be, a
durable workflow - this ADR does not ask such a service to adopt a second scheduling
mechanism for work Temporal already covers.

## Consequences

**Good** — The failure mode this ADR closes - a `@Scheduled` job silently running once per
instance instead of once per tick - is now a build failure, not a discovery made from a
production graph. Both bundled mechanisms were verified end to end against a real
Testcontainers Postgres, not merely assumed to work from their own documentation: ShedLock's
`LockProvider.lock()` was proven to refuse a second concurrent acquisition of the same name,
and a real `Job` bean was proven to actually fire under Quartz's clustered `JobStore` defaults.

**Bad** — Two new tables per mechanism a service adopts (one for ShedLock, eleven for
Quartz), and a fixed version-number reservation (`V002`, `V003`) every service using this
template must respect in its own Flyway sequence - discovered directly while wiring the
first real consumer, when agent-factory's own `V2` migration collided with the bundled
schema and had to be renumbered. Quartz's clustered `JobStore`, like `JobStoreTX` in general,
recovers a dead node's fired triggers only as fast as `clusterCheckinInterval` allows -
neither mechanism is sub-second failover, and neither should be reached for expecting it.

**Neutral** — Two mechanisms living behind one ArchUnit rule, rather than a single sanctioned
choice, mirrors P-130's own two-cache-backend shape (ADR-0013): the rule enforces the
invariant that matters (provably safe under concurrency) without forcing every job into the
heavier of the two tools it does not need.

## Alternatives considered

### A single mandated mechanism (ShedLock only, or Quartz only)

Simpler to document and to enforce. Rejected because the two mechanisms solve genuinely
different problems at genuinely different costs - mandating Quartz for every job forces an
eleven-table schema and a second scheduling engine onto a service that only ever needed "run
this idempotent sweep once a night"; mandating ShedLock alone gives up Quartz's explicit
dead-node recovery and persisted, misfire-aware scheduling for a service that actually needs
it. The same reasoning P-130 already applied to caching backends.

### Plain `@Scheduled`, relying on `spring.task.scheduling` running on exactly one instance

Would require a deployment convention - "only ever run one replica of this service" - that
this repository does not otherwise impose anywhere, and that silently breaks the moment
autoscaling or a rolling deploy briefly runs two. Rejected: the whole point of this ADR is
that "how many instances are running" is not a property scheduled-job code should have to
assume, correctly, forever.

### A Kubernetes `CronJob` (or equivalent scheduler outside the application) per job

Moves the exclusivity guarantee outside the JVM entirely - a real, valid pattern for a batch
process with no need to share the application's own beans, connection pools or transaction
management. Rejected as this repository's default because most of what needs scheduling here
*does* need those - `ProcessedMessageStore`, the application's own `DataSource` - and a
separate deployable per scheduled job multiplies operational surface for jobs that are a few
lines of code once the exclusivity problem is solved. Nothing here prevents a service from
choosing this instead for a specific job; it is simply not what `scheduling-support` builds.

## Revisit when

Quartz or ShedLock ships a major version whose clustering mechanism changes (re-verify
`StdRowLockSemaphore`'s SQL and `JdbcTemplateLockProvider`'s constructor against this ADR's
own recorded evidence, the same discipline ADR-0019 already applies to the OpenTelemetry
agent) - or Spring Framework ships its own first-class distributed-scheduling primitive,
which would remove the reason to reach for either third-party mechanism at all.
