# Technical debt tracker

Debt that is known, deliberate and not yet paid. The point of writing it here rather than leaving a
`FIXME` in the code is that this file is readable without opening the file the debt is in — by a
person planning a sprint, or by an agent asked to "clean up the ordering module".

Checkstyle rejects `FIXME` comments and points here.

## Format

| Id | Where | What | Why it is still here | Cost of leaving it | Trigger to fix |
|---|---|---|---|---|---|
| TD-001 | `libs/messaging-support`, `services/agent-factory` | No test has sent a message through a real Kafka broker and had a `@KafkaListener` receive it | `MessagingSupportAutoConfigurationTest` proves the producer/consumer factories, serializer and dead-letter routing wire up against Boot's autoconfiguration; `AgentActivationAuditListenerTest`-shaped coverage would still need a broker container, which this environment could not run to verify | A regression that only shows up in flight - a header the consumer-side deserializer cannot read, a `@KafkaListener` topic SpEL expression that resolves to the wrong name - would not be caught until a real deployment | Adding `testcontainers-kafka` to `agent-factory` and asserting a published `AgentVersionActivated` is actually consumed and audited |
| TD-002 | `libs/messaging-support` | `RabbitStreamProvisioner` has no integration test | RabbitMQ is not used by any service in the repo yet, so a container in CI would test nothing anyone runs | The compaction refusal is unit-tested, but the stream arguments are not verified against a real broker | The first service that actually publishes over AMQP |
| TD-003 | `docs/principles/P-090-layered-tests.md` | The test-layering rules are review-only | Checking them needs the test classpath, which the architecture test deliberately excludes so that production rules cannot be satisfied by test code | A domain test that quietly starts a Spring context slows the suite and nobody notices | A second architecture test that imports tests, if the suite's runtime becomes a problem |

## Adding an entry

Two rules keep this file honest.

1. **Every row names the cost of leaving it.** "Should be refactored" is not a cost. If nobody can
   say what goes wrong, the entry is a preference and belongs in review.
2. **Every row names an observable trigger**, not a date. "When we next touch it" is a trigger;
   "Q3" is a wish.

An entry whose cost has been paid gets deleted, not marked done. The history is in git.
