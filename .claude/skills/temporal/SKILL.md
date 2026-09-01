---
name: temporal
description: Model a durable, long-running process in this repo with Temporal - when it earns adopting over a use case, an event or a task queue, the @WorkflowDefinition / activity split, the determinism rules that make replay safe, and the worker bootstrap temporal-support supplies. Use whenever a change involves io.temporal, @WorkflowInterface, @ActivityInterface, a WorkflowDefinition, or a process that must survive a crash or run for hours or days.
---

# Temporal

A process that must survive longer than one transaction - crash-resilient, running for
hours, days, or waiting on something external for an unbounded time - is a Temporal
workflow. See [P-033](../../../docs/principles/P-033-workflow-definitions-are-deterministic.md)
for the full reasoning; this skill is the how.

## Decide whether it earns being a workflow at all

| Shape of the work | Use |
|---|---|
| Commits once, inside one transaction | a [use case](../use-case/SKILL.md) |
| A fact any number of interested parties should hear about | an [event](../events/SKILL.md) |
| One background job, fire-and-forget, done or dead within its retry budget | a [task queue](../task-queues/SKILL.md) |
| Must survive a crash, coordinate multiple steps with their own retry/compensation logic, or run for longer than a task's retry budget makes sense for | a Temporal workflow |

Modelling an ordinary use case as a workflow because Temporal was already in the codebase
is the mistake P-033 exists to catch, not a style choice - it costs a great deal of
mechanism (determinism rules, activity boundaries, a worker to run) for something a
`@Transactional` method already does correctly.

## Define the workflow and activity contracts

```java
@WorkflowInterface
public interface AgentDeploymentPipeline {
    @WorkflowMethod
    void run(String agentId, int version);
}

@ActivityInterface
public interface DeploymentActivities {
    @ActivityMethod
    void provision(String agentId, int version);

    @ActivityMethod
    void verify(String agentId, int version);
}
```

These interfaces are Temporal's own annotations (`io.temporal.workflow.*`,
`io.temporal.activity.*`), not this platform's kernel annotations - the kernel role
annotation goes on the *implementation*.

## Implement the workflow: deterministic, framework-free, calls only through an activity stub

```java
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

Never:
- inject anything Spring would resolve - there is no Spring context in scope when Temporal
  constructs this instance, once per execution, from a bare class;
- call `Instant.now()`, `Math.random()`, or anything else ambient - take it as a parameter,
  or read it inside an activity instead;
- call `ActivityOptions.newBuilder()` directly - `WorkflowRules` refuses it. Always
  `TemporalActivityOptions.of(startToCloseTimeout)`, which is the one place a timeout is
  mandatory rather than the "defaults to unlimited" `ActivityOptions`'s own builder allows.

If a rule from `WorkflowRules` fails, the fix is almost always to move whatever it flagged
into an activity - that boundary exists specifically to hold the parts of the process that
are allowed to be non-deterministic.

## Implement the activity: a Spring bean, the boundary where I/O actually happens

```java
@InboundAdapter(AdapterKind.WORKFLOW)
public class DeploymentActivitiesImpl implements DeploymentActivities {

    private final ProvisionDeploymentUseCase provision;   // an @InputPort, called like any adapter

    DeploymentActivitiesImpl(ProvisionDeploymentUseCase provision) {
        this.provision = provision;
    }

    @Override
    public void provision(String agentId, int version) {
        provision.provision(new ProvisionDeploymentCommand(agentId, version));
    }
}
```

Dependency injection into an activity implementation is explicitly fine - Temporal's own
documentation draws this line, discouraging it for workflows and allowing it for
activities. `libs/temporal-support`'s worker bootstrap discovers this bean by its
`@InboundAdapter(AdapterKind.WORKFLOW)` annotation the same way it would discover any other
inbound adapter, and hands the already-constructed instance to the worker.

## Starting and signalling a workflow

```java
@OutboundAdapter(port = DeploymentPipelinePort.class, kind = AdapterKind.WORKFLOW)
public class TemporalDeploymentPipelineAdapter implements DeploymentPipelinePort {

    private final WorkflowClient client;

    @Override
    public void start(String agentId, int version) {
        AgentDeploymentPipeline workflow = client.newWorkflowStub(
                AgentDeploymentPipeline.class,
                WorkflowOptions.newBuilder().setTaskQueue("agent-factory").build());
        WorkflowClient.start(workflow::run, agentId, version);
    }
}
```

`WorkflowClient.start(...)` returns as soon as the workflow is accepted, not when it
finishes - the calling use case's own transaction commits independently of how long the
workflow itself takes, the same "submit after commit" shape [P-131](../../../docs/principles/P-131-task-queues.md)
already uses for a task.

## Configuration

```yaml
acme:
  temporal:
    target: localhost:7233
    namespace: default
    task-queue: agent-factory
    base-packages: [com.acme.agentfactory]
```

One task queue per service is this platform's default, matching one classic queue per
service in `task-queues`. A service with a second, genuinely independent workflow family
is more likely a signal it should be a second service than a reason to add a second queue.

## Testing

`io.temporal:temporal-testing`'s `TestWorkflowEnvironment` is Temporal's own recommended
tool for workflow logic - an in-process, time-skipping test server, so a workflow that
sleeps for a day runs in milliseconds:

```java
TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance();
Worker worker = testEnvironment.newWorker(TASK_QUEUE);
worker.registerWorkflowImplementationTypes(AgentDeploymentPipelineImpl.class);
worker.registerActivitiesImplementations(new DeploymentActivitiesImpl(...));
testEnvironment.start();

AgentDeploymentPipeline workflow = testEnvironment.getWorkflowClient()
        .newWorkflowStub(AgentDeploymentPipeline.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
workflow.run("agent-1", 1);
```

There is no official Testcontainers module for Temporal. For a test that specifically needs
to prove a real, out-of-process server - not workflow logic, which `TestWorkflowEnvironment`
already covers better - `temporalio/temporal:<version>`'s own `server start-dev` command is
the lightest real server available: single container, in-memory persistence, no separate
database to start first the way `temporalio/auto-setup` needs. Wait on its own readiness
banner rather than a container healthcheck, which the image does not define:

```java
new GenericContainer<>(DockerImageName.parse("temporalio/temporal:1.8.2"))
        .withCommand("server", "start-dev", "--ip", "0.0.0.0")
        .withExposedPorts(7233)
        .waitingFor(Wait.forLogMessage(".*Temporal Server:.*\\n", 1));
```

For a complete, real example of both kinds of test side by side, read
`libs/temporal-support/src/test/java/com/acme/temporal/`: `GreetingWorkflowTest` (in-process,
time-skipping) and `TemporalSupportRealServerTest` (a real server in a real container) -
both driving the same real workflow and activity, the classes that proved this whole
integration actually works rather than merely compiling.

## Checklist

- [ ] the process genuinely needs to survive a crash or run longer than a task's retry
      budget makes sense for - not just "Temporal is already here"
- [ ] the workflow implementation is `@WorkflowDefinition`, injects nothing, reads no
      ambient time or randomness
- [ ] every call to the world happens through an activity stub, built with
      `TemporalActivityOptions.of(...)`, never `ActivityOptions.newBuilder()` directly
- [ ] the activity implementation is `@InboundAdapter(AdapterKind.WORKFLOW)`, free to take
      real dependencies
- [ ] a workflow is started with `WorkflowClient.start(...)` after the use case that
      decided to start it has already committed, never from inside its transaction
- [ ] the workflow's `id` resolves to a specification under `docs/use-cases`
