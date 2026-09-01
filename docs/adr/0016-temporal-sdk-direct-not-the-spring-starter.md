# ADR-0016 — Plain `temporal-sdk`, not `temporal-spring-boot-starter`, for durable workflows

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-01 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Some processes this platform needs to model outlive a transaction, a deploy, or a worker
crash - "provision this deployment and verify it, retrying whichever step failed, over
however many hours that takes" - which none of the three mechanisms already adopted fit: a
use case commits once ([P-030](../principles/P-030-use-case-unit-of-application-logic.md)),
an event is a broadcast fact with no notion of "and then wait"
([ADR-0007](0007-broker-neutral-event-contracts.md)), a task is fire-and-forget with no
notion of "and if it fails, do something else"
([ADR-0014](0014-rabbitmq-classic-queues-for-point-to-point-tasks.md)). Temporal is the
established tool for exactly this: workflow code is deterministic and replayed from history
to reconstruct state, activities are the only place that touches the world, and both
survive a worker restart with no persistence code of the platform's own to write.

Temporal officially publishes `io.temporal:temporal-spring-boot-starter`, which would be the
natural first choice - it is what `caching-support` and `grpc-support` build on top of for
their own backends. Verified directly against Maven Central (fetching the actual POM, not
trusting a search index, which was confirmed stale by over a year during this same
investigation): at its latest release, **1.38.0 - the same version as the SDK itself - the
starter's own `dependencyManagement` still imports `spring-boot-dependencies:2.7.18`.**
Its autoconfiguration module's own build script confirms this is deliberate: Boot 3 and 4
are exercised only by overriding the BOM on the *test* classpath, with the comment "main
classes are still compiled against SB 2.7" - Boot 4 is validated as a runtime-compatibility
claim, not a real published target, and the highest version even that override reaches is
4.0.2, one minor behind this repository's 4.1.1.

The plain SDK (`io.temporal:temporal-sdk`, `io.temporal:temporal-testing`, both 1.38.0) has
no such constraint - it is a Java library with no opinion about which web framework, if any,
hosts it. Its own dependency on gRPC (`temporal-serviceclient`, which every workflow and
activity call travels through) resolves through `io.grpc:grpc-bom:1.76.0` and pins
`protobuf-java-util:3.25.8` directly - both older than what this repository's Boot 4.1.1
parent manages (`grpc-java` 1.83.1, `protobuf-java` 4.35.1) for `grpc-support`. Verified with
`mvn dependency:tree -Dincludes=io.grpc,com.google.protobuf` after adding `temporal-sdk`:
every one of those artifacts resolves to the version this repository's own BOM manages,
with no exclusion required - Maven's dependency mediation favours the version declared in
the consuming project's own effective `dependencyManagement` over any version arriving
transitively through an intermediate module's own imported BOM.

## Decision

**Durable workflows are built on plain `io.temporal:temporal-sdk`, wired by hand in
`libs/temporal-support`, never `temporal-spring-boot-starter`.**

- `TemporalSupportAutoConfiguration` builds `WorkflowServiceStubs` from
  `acme.temporal.target` and a `WorkflowClient` from it directly, the same shape
  `grpc-support` would if Boot's own gRPC starters did not already do it - there is no
  first-party Spring autoconfiguration for plain `temporal-sdk` to build on.
- `TemporalWorkerLifecycle`, a `SmartLifecycle` bean, is this platform's own worker
  bootstrap: it scans for `@WorkflowDefinition` classes with `WorkflowDefinitions.scan`,
  discovers activity implementations as `@InboundAdapter(AdapterKind.WORKFLOW)` Spring
  beans, registers both against the one task queue `acme.temporal.task-queue` names, and
  starts and stops the `WorkerFactory` with the container.
- `TemporalActivityOptions` is the one sanctioned way to build `ActivityOptions` -
  `WorkflowRules` in `libs/arch-test` refuses a `@WorkflowDefinition` calling
  `ActivityOptions.newBuilder()` directly, because that builder validates and builds
  cleanly with no timeout at all.
- No exclusions are declared for `io.grpc:*` or `com.google.protobuf:*` transitively pulled
  in by `temporal-serviceclient` - Maven mediation already resolves them to this
  repository's managed versions, confirmed by `dependency:tree`, not assumed.

## Consequences

**Good** — No dependency on Temporal's own Spring integration ever catching up to Boot 4.1,
which - at the time of this decision - it has not, one full major Boot version behind. The
platform's own `TemporalWorkerLifecycle` is small enough (worker construction, two
discovery scans, start/stop) that owning it costs little, and it slots into this platform's
existing shape: a `SmartLifecycle` bean is exactly how a `@KafkaListener` container starts
and stops too, just built here by hand instead of by an autoconfiguration module.

**Bad** — This platform owns worker bootstrap code Temporal's own Spring starter would
otherwise provide, including if a later starter release ever does target Boot 4 properly -
migrating off `TemporalWorkerLifecycle` at that point means comparing what a mature starter
does against what this hand-built version does, not a drop-in swap. The `mvn dependency:tree`
verification this ADR relies on is a snapshot of this repository's dependency versions
today; a future bump to either Boot's or Temporal's managed grpc-java version could reopen a
real conflict that currently resolves cleanly by coincidence of favourable mediation, not
by a compatibility guarantee either project makes.

**Neutral** — `temporal-testing`'s `TestWorkflowEnvironment` (an in-process, time-skipping
test server) is Temporal's own recommended tool for testing workflow logic regardless of
which integration path is chosen, and is unaffected by this decision either way.

## Alternatives considered

### `io.temporal:temporal-spring-boot-starter`

Would give `@ActivityImpl`-style bean discovery and configuration-property-driven worker
setup out of the box, the same convenience `caching-support` and `grpc-support` get from
Boot's own starters. Rejected on current, verified evidence: its own POM imports Boot 2.7 at
its latest release, and its build script's own comments describe Boot 3/4 support as a
runtime-only validation, not a published target - adopting it now would mean depending on
autoconfiguration classes compiled against an API surface two major Boot versions behind
this repository's, with no assurance the "override the BOM and it still works" claim
extends to every autoconfiguration path a real service would exercise.

### Wait for a Boot-4-targeting Temporal Spring starter release

Avoids owning `TemporalWorkerLifecycle` at all. Rejected because there is no published
timeline for one, and the platform's durable-workflow need (a real deployment pipeline this
repository's dogfooding services will exercise) exists now - the hand-built lifecycle bean
is small and replaceable, not a reason to defer the capability itself.

### Model long-running processes as a chain of tasks or events instead of adopting Temporal

Keeps the platform to the three mechanisms already adopted, no new dependency. Rejected
because none of the three actually models "wait, retry the failed step specifically, and
resume where it left off across a crash" - approximating it with tasks and events means
hand-rolling state-machine and retry bookkeeping Temporal already solves, the same
reasoning [ADR-0014](0014-rabbitmq-classic-queues-for-point-to-point-tasks.md) used against
approximating a task queue with Kafka.

## Revisit when

`temporal-spring-boot-starter` publishes a release whose own POM imports a Boot 4.1.x (or
later) BOM as its compiled target, not merely a test-classpath override - re-evaluate
`TemporalWorkerLifecycle` against it at that point. Revisit the dependency-mediation
analysis in this ADR whenever either `spring-boot-dependencies`' managed `grpc-java`/
`protobuf-java` versions or `temporal-sdk`'s own pinned versions change; re-run
`mvn dependency:tree -Dincludes=io.grpc,com.google.protobuf` rather than assuming the
previous resolution still holds.
