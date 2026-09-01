# ADR-0014 — RabbitMQ classic queues for point-to-point tasks, kept apart from event streams

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-01 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Every message this platform moves so far has been a broadcast fact: `@EventContract` records
published to a Kafka topic or a RabbitMQ stream, delivered to every consumer group that cares,
governed by [ADR-0007](0007-broker-neutral-event-contracts.md). A background job is a different
shape of message entirely - "provision this deployment," "send this notification" - meant for
exactly one worker, gone once handled, with no reader that should ever see it twice. Modelling a
job on the broadcast path works until a second consumer group is added for an unrelated reason and
now silently duplicates the job every existing worker already does; modelling a broadcast fact as
a job means the second service that needs to react to it never gets a turn, because the queue has
no concept of a second reader.

The platform already runs RabbitMQ for stream-shaped event traffic (ADR-0007's broker-neutral
event contracts include a RabbitMQ stream option). RabbitMQ's classic queue - the original AMQP
queue type, not the stream feature - is a native competing-consumers primitive: every consumer
bound to one queue receives a disjoint share of its messages, acknowledgement removes a message
permanently, and a nameless default exchange with the queue name as routing key is enough
topology to use it. No new broker, no new client library, no new operational surface - the
question was only whether to reach for that primitive or force the job through the event path.

## Decision

**A background job is a `Task`, declared with `@TaskContract(queue = …)`, delivered over a
RabbitMQ classic queue - never modelled as an `@EventContract`, and never given a consumer
`group` the way an event handler has one.**

- `libs/messaging-support` provisions a durable classic queue and its `.dlq` pair from every
  discovered `@TaskContract`, publishes through the default exchange with the queue name as
  routing key, and applies one retry-then-dead-letter policy per service from
  `acme.messaging.rabbit` - not per task, the same split ADR-0007 makes for Kafka's redelivery
  budget.
- `@TaskHandler` binds a real `@RabbitListener(queues = …)` method and is unconditionally
  `@Idempotent` - a classic queue redelivers on every failure path a broker has, with no
  `AT_MOST_ONCE` escape hatch the way an event contract can declare one for telemetry.
- A task queue's messages are never replayed and never broadcast. If a second consumer will ever
  legitimately need the same fact, that is the signal the work was actually an event, not a task,
  and belongs on `@EventContract` instead - the two are not two configurations of the same idea.
- Kafka is not used for tasks. Kafka's consumer-group rebalancing is built for many consumers
  sharing partitions of an ordered log with replay; a queue with none of ordering, replay or
  multi-group semantics gains nothing from Kafka's machinery and pays its operational weight
  (partition planning, consumer-group coordination) for a primitive RabbitMQ already provides for
  free with a queue declaration.

## Consequences

**Good** — The two things a message can be - a fact for whoever is interested, a job for exactly
one worker - stay two things in the code, not two configurations of one abstraction an engineer
has to remember to set correctly. `TaskContractRules` and `EventContractRules` each enforce what
their own shape actually requires (unconditional idempotency versus delivery-conditional,
uniqueness of queue name versus stream name) without either rule set having to account for the
other's exceptions.

**Bad** — The platform now runs message traffic over two distinct patterns on RabbitMQ (classic
queue for tasks, stream for some events) plus Kafka for others - three shapes of "something moved
through a broker" for an engineer to keep straight, where a single unified abstraction would be
one. `messaging-support` carries three provisioning paths (`RabbitTaskQueueProvisioner`,
`RabbitStreamProvisioner`, `KafkaTopicProvisioner`) instead of one.

**Neutral** — Retry and dead-lettering for tasks reuse Spring AMQP's built-in
`RabbitListenerRetrySettingsCustomizer` and `RepublishMessageRecoverer` rather than anything
`messaging-support` implements itself - the platform's own code here is provisioning and
discovery, not retry machinery.

## Alternatives considered

### Model a task as an `@EventContract` with one consumer group

Reuses one abstraction end to end - one contract annotation, one set of ArchUnit rules, one
provisioning path. It lost because the semantics genuinely differ where it matters: an event's
retention and replay exist so a *new* consumer group can catch up on history, which is precisely
wrong for a job that must never be re-run by an incidental second reader. Forcing single-group-
only would still leave the contract shaped for broadcast, with retention and payload-kind fields
that mean nothing for a job and a `group` field whose only legal value is "the one true group,"
which is a task queue wearing an event's clothes.

### Single-partition Kafka topic per task queue

Kafka can approximate competing consumers within one consumer group, and would keep every message
pattern on one broker technology. It lost on fit: a task queue needs none of what a partitioned
log buys (ordering within a key, replay, multi-group broadcast), so the topic becomes a queue with
a log's operational cost - partition rebalancing, offset management, a `__consumer_offsets`
dependency - paid for a primitive RabbitMQ already gives for free with `QueueBuilder.durable(name)`.

### A dedicated job-queue system (e.g. a database-backed queue, or a purpose-built scheduler)

Purpose-built job queues often add features this platform does not yet need - scheduling, job
priorities, a dashboard. It lost because it is a new dependency and a new failure mode for
capability RabbitMQ's classic queue already provides, and because it would sit outside
`messaging-support`'s existing role as the only module that knows a broker exists.

## Revisit when

A task needs a feature classic queues do not have - priority ordering, delayed delivery beyond
what a dead-letter TTL trick provides, or a job that must fan out to a known, fixed set of workers
rather than compete among an unknown number of them. Revisit the three-provisioning-path cost if a
fourth broker pattern is ever proposed; at that point a shared provisioning abstraction may earn
its complexity where it does not yet.
