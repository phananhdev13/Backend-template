# P-131 — Task queues are point-to-point, never broadcast

| | |
|---|---|
| **Layer** | domain |
| **Enforced by** | `TaskContractRules.everyTaskDeclaresAContract()`, `TaskContractRules.everyTaskHandlerIsIdempotent()`, `TaskContractRules.handledTaskDeclaresAContract()`, `TaskContractRules.personalDataTasksCarryAnAdr()`, `TaskContractRules.queueNamesAreUnique()` in `libs/arch-test` |
| **Annotations** | `@TaskContract`, `@TaskHandler`, `@Idempotent`, `@Adr` |
| **Guide** | [skill: task-queues](../../.claude/skills/task-queues/SKILL.md) |

## Rule

A `Task` is work for exactly one consumer, delivered once and then gone — the opposite shape
from an [event](P-070-event-semantics.md), which broadcasts a fact to every interested
consumer group for as long as its retention says. A task declares `@TaskContract` naming its
queue; the class that consumes it declares `@TaskHandler` naming the task it handles and is
unconditionally `@Idempotent`, because a classic queue redelivers on every failure path a
broker has, with no at-most-once escape hatch.

## Why

**A background job and a domain fact are different things wearing similar clothes, and
modelling one as the other costs differently depending on which way you get it wrong.**
Model a job as a broadcast event and every consumer group receives it, including ones added
after the fact who now duplicate work meant for exactly one worker. Model a fact as a task
and it vanishes into a competing-consumers queue the moment two services both need to react
to it — the second reaction never happens, silently, because the queue does not know a
second reader exists. `libs/messaging-support` provisions a RabbitMQ classic queue for a
`Task` and a Kafka topic or RabbitMQ stream for an event; the two are not interchangeable at
the broker, and should not be interchangeable in the domain either.

**There is no `group` on a `@TaskHandler`, and that omission is the point.** A classic
queue's whole mechanism is that every consumer bound to it competes for the same messages, so
scaling a worker out is "start another instance," never a topology decision made in code the
way an event consumer's group is ([P-042](P-042-event-handlers-delivery-contract.md)).
Reintroducing a group-like concept here would just be reinventing consumer groups on top of
a primitive that already does the right thing without one.

**Every task queue redelivers, unconditionally — there is no `AT_MOST_ONCE` to opt out
with.** An event contract can legitimately declare `AT_MOST_ONCE` for telemetry that is
summarised anyway; a task has no equivalent field, because a crashed worker, a rejected
message, or a container restart mid-handling all redeliver regardless of what anyone
declares. A `@TaskHandler` that is not `@Idempotent` will repeat its side effect the first
time any of those ordinary failure modes happens, not in some rare edge case.

**Retry policy is a deployment decision, not a per-task one — the same split
[P-042](P-042-event-handlers-delivery-contract.md) makes for Kafka's redelivery budget.**
`@TaskContract` declares only the queue's identity, because how hard to retry before giving
up before dead-lettering is answered once, for every queue in a service, in
`acme.messaging.rabbit`. A per-task retry budget would be one more place that number could
drift from what the deployment can actually sustain.

**A queue nobody names twice is a queue two task types cannot silently collide on.**
`TaskContractRules.queueNamesAreUnique()` treats a shared queue name the same way
`EventContractRules.streamIdentifiersAreUnique` treats a shared stream: a whole-classpath
property no single class can see, and a collision here means a consumer cannot tell which
contract's payload shape it is about to deserialise.

## In code

```java
@TaskContract(queue = "agents.provision-deployment")
public record ProvisionAgentDeploymentTask(String agentId, int version, Instant submittedAt)
        implements Task {}
```

Submitted only after the fact it reacts to has actually committed — never from inside the
`@Transactional` use case, for the same reason [P-072](P-072-transactional-outbox.md) forbids
a broker call inside a transaction:

```java
@OutboundAdapter(port = TaskPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(value = {"P-051", "P-072", "P-131"}, note = "…")
public class AgentDeploymentProvisioningAdapter {

    @ApplicationModuleListener
    public void on(AgentVersionActivated event) {
        tasks.submit(new ProvisionAgentDeploymentTask(event.agentId(), event.version(), Instant.now(clock)));
    }
}
```

The handler is unconditionally idempotent, with no delivery-guarantee check to make first:

```java
@TaskHandler(handles = ProvisionAgentDeploymentTask.class)
@Idempotent(key = "agentId", note = "The deployment row is an upsert keyed by (agentId, version)")
@InboundAdapter(AdapterKind.MESSAGING)
public class ProvisionAgentDeploymentWorker {

    @RabbitListener(queues = "agents.provision-deployment")
    @Transactional
    public void on(ProvisionAgentDeploymentTask task) { … }
}
```

Wrong — submitted inside the transaction it should wait for, and with no idempotency:

```java
@Transactional
public void activateVersion(ActivateAgentVersionCommand command) {
    agents.save(agent);
    tasks.submit(new ProvisionAgentDeploymentTask(...));   // may still roll back
}

@TaskHandler(handles = ProvisionAgentDeploymentTask.class)   // no @Idempotent
@RabbitListener(queues = "agents.provision-deployment")
public void on(ProvisionAgentDeploymentTask task) {
    jdbc.sql("insert into agent_deployment (...) values (...)").update();   // repeats on redelivery
}
```

## Enforcement

`TaskContractRules.everyTaskDeclaresAContract()` fails a `Task` implementation with no
`@TaskContract`. `TaskContractRules.handledTaskDeclaresAContract()` fails a `@TaskHandler`
whose `handles()` type declares none. `TaskContractRules.queueNamesAreUnique()` fails two
task types claiming the same queue, across the whole import:

```
"agents.provision-deployment" is claimed by [com.acme.agentfactory...ProvisionAgentDeploymentTask,
com.acme.agentfactory...ReindexAgentTask]
See docs/principles/P-131-task-queues.md
```

`TaskContractRules.everyTaskHandlerIsIdempotent()` fails a `@TaskHandler` with no
`@Idempotent` — unconditionally, unlike `EventContractRules.atLeastOnceHandlersAreIdempotent`,
which only fires when the paired contract's `delivery()` is at-least-once.

`TaskContractRules.personalDataTasksCarryAnAdr()` fails a `@TaskContract` with
`containsPersonalData = true` carrying no `@Adr`.

## Deviating

A task whose handler is naturally idempotent without a stored de-duplication key — an
upsert keyed by the task's own business identifier, or a state transition that is a no-op
from its own target state — satisfies `@Idempotent` with a `note` explaining why, the same
as an event handler does.

Related: [P-070](P-070-event-semantics.md) for when the work is a fact to broadcast rather
than a job for one worker; [P-072](P-072-transactional-outbox.md) for why submission waits
for commit.
