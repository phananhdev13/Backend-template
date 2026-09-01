---
name: task-queues
description: Submit and consume background jobs in this repo over a RabbitMQ classic queue - point-to-point, competing consumers, no replay - as distinct from the broadcast, replayable events skill covers. Declaring @TaskContract and @TaskHandler, submitting after commit, dead-lettering and retry. Use whenever a change involves a background job, a worker, a classic queue, or when a TaskContractRules failure needs interpreting.
---

# Task queues

A `Task` is work for exactly **one** consumer, delivered once and then gone. An
[event](../events/SKILL.md) is a fact broadcast to every interested consumer group for as long as
its retention says. They look similar - both cross a broker, both get a `@…Contract` annotation -
and modelling one as the other costs differently depending on which way you get it wrong:

| | `Task` | Event |
|---|---|---|
| Consumers | exactly one, whichever worker picks it up | every registered consumer group |
| Adding a second reader | duplicates the work - two workers now compete for the same jobs | free - a new group sees every message from now on |
| Replay | none - once handled, it's gone | as far as retention allows |
| Ordering | none promised | `PER_KEY` by default |
| Broker shape | RabbitMQ classic queue | Kafka topic or RabbitMQ stream |

Model a job as a broadcast event and every consumer group receives it, including ones added later
who now duplicate work meant for exactly one worker. Model a fact as a task and it vanishes into a
competing-consumers queue the moment a second service needs to react to it - that second reaction
never happens, silently.

## Declare the contract

```java
@TaskContract(queue = "agents.provision-deployment")
public record ProvisionAgentDeploymentTask(String agentId, int version, Instant submittedAt)
        implements Task {}
```

`queue` is the only identity a task declares - no `group`, unlike an event's `@EventHandler`. A
classic queue's whole mechanism is that every consumer bound to it competes for the same
messages, so scaling a worker out is "start another instance," never a topology decision made in
code. `TaskContractRules.queueNamesAreUnique` fails two task types claiming the same queue, across
the whole import - a collision means a consumer cannot tell whose payload it is about to
deserialise.

There is no per-task retry field either. How hard to retry before dead-lettering is a deployment
decision, answered once for every queue in a service under `acme.messaging.rabbit` in
`application.yml` (`max-delivery-attempts`, `retry-initial-interval-ms`, `retry-multiplier`,
`retry-max-interval-ms`) - the same split events make for Kafka's redelivery budget. A per-task
budget would be one more place that number could drift from what the deployment can sustain.

## Submit after commit, never inside the transaction

Same reasoning as publishing an event: a task submitted inside the transaction that decided it is
still submitted if that transaction then rolls back. React to the already-committed fact instead:

```java
@OutboundAdapter(port = TaskPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(value = {"P-051", "P-072", "P-131"}, note = "…")
public class AgentDeploymentProvisioningAdapter {

    private final TaskPublisher tasks;

    @ApplicationModuleListener
    public void on(AgentVersionActivated event) {
        tasks.submit(new ProvisionAgentDeploymentTask(event.agentId(), event.version(), Instant.now(clock)));
    }
}
```

The use case that activated the version never calls `TaskPublisher` itself -
`OutboxRules.noBrokerCallInsideATransaction` refuses a `@UseCase` depending on
`com.acme.messaging`, and the refusal is protecting exactly this.

## Consuming

`@TaskHandler` states intent; it wires nothing by itself. The class still needs a real
`@RabbitListener` method naming the queue, or nothing ever calls it - a gap that is easy to miss
because the class compiles and the architecture test passes either way.

```java
@TaskHandler(handles = ProvisionAgentDeploymentTask.class)
@Idempotent(key = "agentId", note = "The deployment row is an upsert keyed by (agentId, version)")
@InboundAdapter(AdapterKind.MESSAGING)
public class ProvisionAgentDeploymentWorker {

    private final JdbcClient jdbc;

    @RabbitListener(queues = "agents.provision-deployment")
    @Transactional
    public void on(ProvisionAgentDeploymentTask task) {
        jdbc.sql("""
                        insert into agent_deployment (agent_id, version_number, provisioned_at)
                        values (:agentId, :version, :provisionedAt)
                        on conflict (agent_id, version_number) do nothing
                        """)
                .param("agentId", task.agentId())
                .param("version", task.version())
                .param("provisionedAt", task.submittedAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
```

Binding `task.submittedAt()` (an `Instant`) straight into a `timestamptz` column: see the
`persistence` skill's note on why that needs `.atOffset(ZoneOffset.UTC)` first - pgjdbc has no
default SQL type for `Instant`, and the failure is a `BadSqlGrammarException`, not an obviously
type-related one.

`@Idempotent` is **unconditional** on a `@TaskHandler` -
`TaskContractRules.everyTaskHandlerIsIdempotent` fails one with none, with no delivery-guarantee
check first the way `EventContractRules.atLeastOnceHandlersAreIdempotent` has. A classic queue
redelivers on every failure path a broker has - a crashed worker, a rejected message, a container
restart mid-handling - with no `AT_MOST_ONCE` escape hatch to opt out with. The upsert above is
what actually keeps that promise; `@Idempotent` alone is documentation.

## Failure handling

A task that fails every retry attempt configured in `acme.messaging.rabbit` lands on
`<queue>.dlq`, published through the default exchange with the failing message's own
`receivedRoutingKey + ".dlq"` as the routing key - `RabbitTaskQueueProvisioner` already declared
that queue alongside the task's own. This is the one place a task queue's failure handling differs
operationally from an event's dead-letter topic: same idea, RabbitMQ-native mechanism.

For a complete, real worker rather than this illustration, read
`services/agent-factory/.../adapter/in/messaging/ProvisionAgentDeploymentWorker.java` and its
publisher-side pair `AgentDeploymentProvisioningAdapter.java` - the classes that proved this whole
consuming section actually works against a real broker rather than merely compiling.

## Checklist

- [ ] the work is really point-to-point - if a second consumer will ever need the same fact, this
      should be an event, not a task
- [ ] `@TaskContract(queue = …)` names a queue no other task type claims
- [ ] submitted from an `@ApplicationModuleListener` reacting to a committed fact, never from
      inside the transaction that decided it
- [ ] `@TaskHandler` is paired with a real `@RabbitListener(queues = …)` method
- [ ] the handler is `@Idempotent`, and the method body actually is (an upsert, or a no-op from
      its own target state) - not just the annotation
- [ ] any `Instant` parameter bound through `JdbcClient` is converted with `.atOffset(ZoneOffset.UTC)` first
- [ ] `containsPersonalData = true` carries an `@Adr`
