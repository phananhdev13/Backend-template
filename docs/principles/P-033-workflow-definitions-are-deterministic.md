# P-033 — Workflow definitions are deterministic, and call the world only through an activity

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `WorkflowRules.workflowDefinitionsStayFrameworkFree()`, `WorkflowRules.workflowsBuildActivityOptionsThroughTheSanctionedFactory()` in `libs/arch-test` |
| **Annotations** | `@WorkflowDefinition`, `@InboundAdapter(AdapterKind.WORKFLOW)` |
| **Guide** | [skill: temporal](../../.claude/skills/temporal/SKILL.md) |

## Rule

A process that must survive longer than one transaction - that can outlive a deploy, a
crash, or days of waiting on something external - is a `@WorkflowDefinition`, not a use
case with a retry loop bolted on. A workflow definition depends on nothing Spring would
inject, reads no ambient clock, and touches the outside world only by calling an activity
through `Workflow.newActivityStub`, built with `TemporalActivityOptions`, never
`ActivityOptions.newBuilder()` directly. The activity implementation that stub calls into is
a Spring bean, `@InboundAdapter(AdapterKind.WORKFLOW)`, the boundary where the workflow's
orchestration actually touches I/O.

## Why

**Temporal reconstructs a workflow's current state by replaying its history through the
same code, not by loading a snapshot.** Every decision the workflow made - which branch it
took, what an activity returned, how long it slept - is a recorded event; resuming after a
worker crash means running the workflow function again from the top, with those recorded
answers fed back in place of actually redoing the work. A workflow that depends on
anything that could answer differently the second time - a Spring-injected client hitting
a real endpoint, `Instant.now()`, `Math.random()` - now disagrees with its own history, and
Temporal fails the execution with `NonDeterministicException`. This is the same failure
mode `DomainRules.domainDoesNotReadTheSystemClock` already guards against in the domain, for
exactly the same reason, transposed onto a process that keeps running for days instead of
one method call.

**A workflow that is allowed to call out directly loses the one thing durability was for.**
An HTTP call, a database write, a message publish made directly from workflow code happens
during replay too - every single time the workflow is replayed, which is not once. An
activity is Temporal's answer: its result is recorded once, replayed as data thereafter,
which is what makes "call another service" safe to do from inside a process that might
re-execute its own code from scratch at 3am after a worker restart.

**`ActivityOptions` builds cleanly with no timeout at all, and that is the RPC-with-no-deadline
mistake one layer deeper.** [P-051](P-051-remote-call-resilience.md) already refuses an
`@OutboundAdapter` with no declared timeout for exactly this reason; an activity call with
no `StartToCloseTimeout` blocks the workflow waiting on it for as long as the activity
does, which for a workflow can mean the life of the process.
`TemporalActivityOptions.of(Duration)` is the one place that obligation is actually
enforced, because `ActivityOptions`'s own builder does not enforce it.

**Dependency injection into a workflow instance is Temporal's own documented warning, not
a stylistic preference this platform adds on top.** Temporal constructs a workflow
implementation itself, once per execution, from a bare class - there is no Spring context
in scope to inject from at that point even if the SDK allowed it, and reaching for one
anyway (a static singleton, a ThreadLocal) reintroduces exactly the ambient, replay-unsafe
state the whole model exists to forbid.

## In code

```java
@WorkflowInterface
public interface AgentDeploymentPipeline {
    @WorkflowMethod
    void run(String agentId, int version);
}

@WorkflowDefinition(id = "WF-AGT-001", value = "Provisions and verifies an agent deployment")
public class AgentDeploymentPipelineImpl implements AgentDeploymentPipeline {

    private final DeploymentActivities activities =
            Workflow.newActivityStub(DeploymentActivities.class, TemporalActivityOptions.of(Duration.ofMinutes(5)));

    @Override
    public void run(String agentId, int version) {
        activities.provision(agentId, version);
        activities.verify(agentId, version);
    }
}
```

The activity implementation is a Spring bean, wired with whatever it actually needs to do
its work - the boundary a workflow's determinism exists to push everything real across:

```java
@InboundAdapter(AdapterKind.WORKFLOW)
public class DeploymentActivitiesImpl implements DeploymentActivities {

    private final ProvisionDeploymentUseCase provision;   // an @InputPort, called like any adapter

    @Override
    public void provision(String agentId, int version) {
        provision.provision(new ProvisionDeploymentCommand(agentId, version));
    }
}
```

Wrong - a workflow reading the clock and calling an activity with no timeout, the two
mistakes this principle exists to catch in one method:

```java
@WorkflowDefinition(id = "WF-…")
public class BadWorkflowImpl implements SomeWorkflow {
    @Override
    public void run() {
        Instant startedAt = Instant.now();               // ambient, disagrees on replay
        DeploymentActivities activities = Workflow.newActivityStub(
                DeploymentActivities.class, ActivityOptions.newBuilder().build());  // no timeout
        activities.provision(...);
    }
}
```

## Enforcement

`WorkflowRules.workflowDefinitionsStayFrameworkFree()` fails a `@WorkflowDefinition` class
depending on Spring, Jakarta, Hibernate, Jackson, Kafka, RabbitMQ, gRPC or protobuf types -
the same infrastructure list `DomainRules.domainDependsOnlyOnDomain()` holds the domain to,
with `io.temporal..` the one addition a workflow definition is allowed that the domain is
not. `WorkflowRules.workflowsBuildActivityOptionsThroughTheSanctionedFactory()` fails a
`@WorkflowDefinition` class calling `ActivityOptions.newBuilder()` directly rather than
through `TemporalActivityOptions`.

`TraceabilityRules.everyWorkflowDefinitionIsDocumented()` fails a `@WorkflowDefinition`
whose `id` resolves to no file under `docs/use-cases`.

## Deviating

A process a use case's own transaction can finish - anything that does not need to survive
a crash or wait on something slower than a database round trip - has no reason to become a
workflow at all; modelling an ordinary use case as a `@WorkflowDefinition` for the sake of
using Temporal is the mistake to fix, not a deviation to record. Reading time inside an
activity implementation is unremarkable - an activity is not replayed, only its recorded
result is - the constraint is on the workflow definition itself.

Related: [P-051](P-051-remote-call-resilience.md) for why a remote call needs a declared
timeout; [P-043](P-043-grpc-internal-rpc.md) and [P-131](P-131-task-queues.md) for the two
other shapes work between services already takes here, neither of them durable across a
crash the way a workflow is.
