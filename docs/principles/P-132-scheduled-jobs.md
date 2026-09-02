# P-132 — A scheduled job is exclusive across instances by construction, never by convention

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `SchedulingRules.schedulerAdaptersAreClusterSafe()` in `libs/arch-test` |
| **Annotations** | `@InboundAdapter(AdapterKind.SCHEDULER)`, `@Adr` |
| **Guide** | [skill: scheduling](../../.claude/skills/scheduling/SKILL.md) |

## Rule

Every service this template ships runs more than one instance in production. A class
annotated `@InboundAdapter(AdapterKind.SCHEDULER)` - a clock tick driving a use case, the
same shape as an HTTP or messaging inbound adapter - must be provably safe to run on more
than one of those instances at the same tick: a real `@SchedulerLock` method (ShedLock), a
real `org.quartz.Job` implementation running under Quartz's clustered `JobStore`, or an
`@Adr` justifying safety some other way. A `@Scheduled` method with none of these is not a
scheduled job; it is N unsynchronized copies of one, where N is however many instances
happen to be running.

## Why

**"It only runs on one instance" is not a fact about the code; it is a fact about the
deployment, and deployments change.** A service that runs one replica today autoscales
tomorrow, or a rolling deploy briefly runs the old and new version together - both are
normal operation, not an edge case. Code that is correct only under an assumption the
deployment does not enforce is not correct; it is correct until the next scaling event, which
is a worse failure mode than an obvious bug because nothing about the code itself changed
when it stopped being true.

**A `@Scheduled` method has no idea a second instance exists, by design.** Spring's task
scheduler is local to the JVM it runs in; there is no ambient coordination, no default that
makes two instances aware of each other's tick. This is not a gap Spring forgot to close -
coordinating instances is a real, separate concern, exactly the size of problem ShedLock and
Quartz's clustered `JobStore` each exist to solve one way.

**A purge, a resubmission, a report generated twice is not "wasted work"; it is the failure
mode.** A read-only job running twice is merely inefficient. A job that deletes, that
publishes, that charges - the exact shape of `IdempotencyLedgerHousekeepingJob`'s own
`ProcessedMessageStore.purgeExpired()`, or a job that resubmits a stuck task - running twice
concurrently is two competing writers with no arbitration, the same failure two consumers in
one group racing a message would be if `@Idempotent` did not exist
([P-071](P-071-idempotency.md)). The difference is that nothing here delivers "the same tick"
twice on purpose the way a broker redelivers on purpose; the duplication is pure accident of
how many replicas happened to be running.

**ShedLock and Quartz's clustered `JobStore` are not the same guarantee, and the gap between
them is what a node dying mid-job costs.** ShedLock's lock survives until `lockAtMostFor`
elapses, then the next tick's instance proceeds - a best-effort guarantee, and exactly right
for an idempotent maintenance job where a delayed retry costs nothing. Quartz's cluster
explicitly recovers a dead node's fired-but-unfinished triggers via its `ClusterManager`
check-in, rather than waiting out a fixed timeout - the right trade when a job's absence for
however long `lockAtMostFor` would be is itself the problem, or when the job needs Quartz's
other real features: misfire policies, blackout calendars, a schedule that survives a
redeploy of the trigger definitions themselves. See ADR-0020 for the full comparison,
including the third option: a service that already depends on `temporal-support` reaches for
Temporal's own native cron schedule instead of either mechanism here, for any job that
already is, or should be, a workflow.

**Neither mechanism can be a suggestion, because a scheduled job silently running once per
instance produces no error anywhere.** No exception, no failed health check, no red build in
the ordinary case - the tell only shows up as a graph nobody was watching, or a table growing
faster than expected. That is exactly the shape of bug a static rule can catch and a runtime
symptom cannot: the wiring is checkable before deploy, the consequence is not observable
until well after.

## In code

The real class, from `agent-factory` - the idempotency ledger `messaging-support` has kept
since P-071 existed, finally swept on a schedule:

```java
@InboundAdapter(AdapterKind.SCHEDULER)
public class IdempotencyLedgerHousekeepingJob {

    private final ProcessedMessageStore processed;

    public IdempotencyLedgerHousekeepingJob(ProcessedMessageStore processed) {
        this.processed = processed;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "idempotency-ledger-housekeeping", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void purgeExpiredDeliveries() {
        processed.purgeExpired();
    }
}
```

`scheduling-support`'s `ShedLockAutoConfiguration` supplies the `LockProvider` bean from
whatever `DataSource` the service already has, and turns on `@EnableScheduling` - a service
that adds the dependency and the Flyway location for `V002__shedlock.sql` gets the rest for
free.

Quartz, for a job that needs the clustered `JobStore`'s explicit dead-node recovery instead
of ShedLock's timeout:

```java
public class NightlyReportJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        // Quartz's own clustered JobStore already guarantees this fires on exactly one
        // node - no @SchedulerLock needed here, and none would help: the lock this rule
        // asks for is `implements org.quartz.Job`, not an annotation.
    }
}
```

`scheduling-support`'s `QuartzClusteringAutoConfiguration` supplies the `spring.quartz.*`
defaults that make Boot's own `QuartzAutoConfiguration` build a clustered `Scheduler` instead
of an in-memory one; the service registers the `JobDetail` and `Trigger` beans and adds the
Flyway location for `V003__quartz_tables.sql`.

Wrong - fires once per instance, not once per tick:

```java
@InboundAdapter(AdapterKind.SCHEDULER)
public class IdempotencyLedgerHousekeepingJob {

    @Scheduled(cron = "0 0 3 * * *")     // no @SchedulerLock, no org.quartz.Job - every
    public void purgeExpiredDeliveries() { … }   // replica does this at 3am, every night
}
```

## Enforcement

`SchedulingRules.schedulerAdaptersAreClusterSafe()` fails a `@InboundAdapter(SCHEDULER)`
class with no `@SchedulerLock` method and no `org.quartz.Job` implementation:

```
com.acme.agentfactory.registry.adapter.in.scheduled.IdempotencyLedgerHousekeepingJob is
@InboundAdapter(SCHEDULER) with no cross-instance exclusivity: no @SchedulerLock method
(ShedLock) and it does not implement org.quartz.Job (Quartz's clustered JobStore). Add one,
or suppress with @Adr if this job is safe under concurrent execution some other way - an
idempotent claim with SELECT ... FOR UPDATE SKIP LOCKED, for example.
See docs/principles/P-132-scheduled-jobs.md
```

The rule checks by name only - `net.javacrumbs.shedlock.spring.annotation.SchedulerLock` and
`org.quartz.Job`, since `libs/arch-test` depends on neither library - so it cannot see
*whether* the lock name is sensible or the cron expression is correct, the same limit
`TaskContractRules` has on a queue name's business meaning. It also cannot verify a service
actually added the matching Flyway migration location; a `@SchedulerLock`-annotated method in
a service with no `shedlock` table fails at startup, loudly, which is the failure mode this
principle is comfortable leaving to Spring's own bean wiring.

## Deviating

A job that only reads - a metrics scrape, a cache warm that tolerates being redundant - may
skip both mechanisms behind an `@Adr` recording that duplication costs nothing here. The
outbox relay pattern P-072 illustrates (`SELECT ... FOR UPDATE SKIP LOCKED` claiming a batch)
is itself a legitimate third way to be safe under concurrency without either ShedLock or
Quartz, and the rule accepts it the same way: via `@Adr`, naming the actual mechanism.

A service that already depends on `temporal-support` and is scheduling work that already is,
or should be, a durable workflow uses Temporal's own `WorkflowOptions.setCronSchedule`
instead - that workflow is `@WorkflowDefinition`-shaped, governed by
[P-033](P-033-workflow-definitions-are-deterministic.md), and never carries
`@InboundAdapter(AdapterKind.SCHEDULER)` at all, so this rule does not apply to it and no
`@Adr` is needed to explain the absence.
