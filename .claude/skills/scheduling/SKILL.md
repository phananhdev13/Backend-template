---
name: scheduling
description: Run a job on a schedule in this repo, safely across more than one instance - choosing between ShedLock (a JDBC lock on a plain @Scheduled method), Quartz's clustered JDBC JobStore, and Temporal's native cron for a service that already runs workflows. Use whenever a change involves @Scheduled, a cron expression, a nightly/periodic job, or when a SchedulingRules failure needs interpreting.
---

# Scheduling

Every service this template ships runs more than one instance in production. A plain
`@Scheduled` method has no idea another instance exists - it fires on every one of them, on
every tick. `@InboundAdapter(AdapterKind.SCHEDULER)` is this repository's existing marker for
"a clock tick drives a use case"; this skill is about the second thing every such class needs -
proof it is safe to run twice at once - and which of three mechanisms to reach for.
See [P-132](../../../docs/principles/P-132-scheduled-jobs.md) and
[ADR-0020](../../../docs/adr/0020-shedlock-and-quartz-for-distributed-cron.md) for the full
reasoning.

## Choosing a mechanism

| | ShedLock | Quartz clustered `JobStore` | Temporal native cron |
|---|---|---|---|
| Already a dependency? | add `scheduling-support` | add `scheduling-support` | only if the service already runs `temporal-support` |
| Schema cost | one table (`shedlock`) | eleven tables (`QRTZ_*`) | none - Temporal's own server owns it |
| A node dying mid-job | lock releases after `lockAtMostFor`; next tick proceeds | `ClusterManager` explicitly recovers the dead node's fired trigger | workflow history resumes exactly where it left off |
| Reach for it when | a short, idempotent maintenance job - a delayed retry costs nothing | the job's absence for `lockAtMostFor` is itself a problem, or you need misfire policies, blackout calendars, or a schedule that survives redeploying the trigger definitions | the scheduled work already is, or should be, a durable workflow |

Never adopt Quartz only to get its cron feature if the service already runs Temporal - use
`WorkflowOptions.setCronSchedule` instead, and the job is `@WorkflowDefinition`-shaped, not
`@InboundAdapter(AdapterKind.SCHEDULER)` at all (see the `temporal` skill).

## ShedLock

Add the dependency and the schema:

```xml
<dependency>
  <groupId>com.acme</groupId>
  <artifactId>scheduling-support</artifactId>
</dependency>
<dependency>
  <groupId>net.javacrumbs.shedlock</groupId>
  <artifactId>shedlock-spring</artifactId>
</dependency>
<dependency>
  <groupId>net.javacrumbs.shedlock</groupId>
  <artifactId>shedlock-provider-jdbc-template</artifactId>
</dependency>
```

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/migration/scheduling-shedlock
```

`ShedLockAutoConfiguration` wires the `LockProvider` from whatever `DataSource` the service
already has, and turns on `@EnableScheduling` - nothing else to configure. Write the job:

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

`name` must be unique across everything this service schedules - it is the row in the
`shedlock` table every instance races to claim. `lockAtMostFor` is a safety ceiling, not a
target: how long the lock survives a node dying mid-job, set generously above the job's real
worst-case duration - too tight and a slow-but-healthy run gets treated as dead and raced by
the next tick. `lockAtLeastFor` guards the opposite failure: a job that finishes in
milliseconds but whose tick interval is shorter than clock skew between instances, so the
next instance does not immediately re-run the same tick.

## Quartz

Add the dependency and the schema:

```xml
<dependency>
  <groupId>com.acme</groupId>
  <artifactId>scheduling-support</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>
```

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/migration/scheduling-quartz
```

`QuartzClusteringAutoConfiguration` supplies the `spring.quartz.*` defaults that make Boot's
own `QuartzAutoConfiguration` build a clustered `Scheduler` (`isClustered=true`,
`instanceId=AUTO`, the Postgres driver delegate, `initialize-schema=never` since Flyway owns
the schema) - override any of them in the service's own `application.yml` exactly as if this
class did not exist. Write the job and register it as beans - Boot auto-detects every
`JobDetail` and `Trigger` bean in the context:

```java
public class NightlyReportJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        // No @SchedulerLock here, and none would help - the clustered JobStore's own
        // SELECT ... FOR UPDATE against QRTZ_LOCKS already guarantees exactly one node fires.
    }
}
```

```java
@Bean
JobDetail nightlyReportJobDetail() {
    return JobBuilder.newJob(NightlyReportJob.class).withIdentity("nightly-report").storeDurably().build();
}

@Bean
Trigger nightlyReportTrigger(JobDetail nightlyReportJobDetail) {
    return TriggerBuilder.newTrigger()
            .forJob(nightlyReportJobDetail)
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?"))
            .build();
}
```

## Both at once

A service can add both `scheduling-shedlock` and `scheduling-quartz` Flyway locations
together - `V002` and `V003` are reserved so they never collide, and neither mechanism
conflicts with the other's schema.

## Checklist

- [ ] the class is `@InboundAdapter(AdapterKind.SCHEDULER)`
- [ ] it carries a real `@SchedulerLock` method, implements `org.quartz.Job`, or the service
      already runs the job as Temporal's native cron instead (no `@InboundAdapter` at all in
      that case)
- [ ] `SchedulingRules.schedulerAdaptersAreClusterSafe` passes - run
      `mvn -pl <service> -am test -Dtest=ArchitectureTest` if unsure
- [ ] the Flyway location for the mechanism used is on `spring.flyway.locations`
- [ ] `lockAtMostFor` (ShedLock) is set generously above the job's real worst-case duration,
      not left at `ShedLockAutoConfiguration`'s `PT5M` default without checking it fits
- [ ] the job body is actually safe to run twice if the lock's guarantee is ever the
      best-effort kind - an upsert, a `WHERE expires_at < now()` delete, not an unconditional
      append
