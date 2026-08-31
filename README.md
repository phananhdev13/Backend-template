# Backend template

A Spring Boot 4.1 monorepo where the architecture is executable.

Most templates document their conventions and hope. Here every class declares its architectural role
with an annotation, every rule that can be checked is an ArchUnit rule, and every rule names the
principle document that explains it. A violation is a red build with a message telling you what to
read — and "what actually implements P-071?" is a question with a generated answer.

It is built to be worked on by people **and** by coding agents, which turns out to want the same
things: a short entry point, knowledge that loads on demand, and feedback that arrives in seconds
rather than in review.

## What is here

```
AGENTS.md              the entry point — a table of contents, not a manual
.claude/               skills, subagents, commands and hooks
libs/kernel            the annotations. No framework dependencies, ever.
libs/arch-test         the ArchUnit rules that make the annotations load-bearing
libs/*-support         web, persistence, messaging, observability
services/order-service the first worked example: REST, JPA, the transactional outbox
services/agent-factory a second service, adding the one thing order-service does not
                        need - a real @KafkaListener consumer with idempotency and a DLQ
docs/                  principles, decisions, guides, specifications
tools/                 the checks that cannot live in Maven
```

## Try it

```bash
mvn verify                                                   # the full gate
mvn -pl services/order-service -am test -Dtest=ArchitectureTest   # 59 architecture rules
tools/principle-map.sh && cat docs/reference/principle-map.md     # what enforces what
```

To see the point of it, break something on purpose:

```java
// services/.../domain/OrderPlaced.java
retention = StreamRetention.COMPACTED   // was TIME_WINDOW
```

```
OrderPlaced declares retention=COMPACTED with payload=FACT. Compaction keeps only the newest
message per key, so earlier facts are deleted and a replay cannot rebuild state. Either publish
the whole current state and set payload=STATE_SNAPSHOT, or use retention=TIME_WINDOW.
See docs/principles/P-070-event-semantics.md
```

That is the whole idea: a mistake whose consequence appears months later, in someone else's
consumer, caught in one second with the reasoning attached.

## The three layers

**Annotations** (`libs/kernel`) name what a class is — `@AggregateRoot`, `@UseCase`, `@OutputPort`,
`@EventContract`. They carry no framework meaning, so the domain and application layers stay
runnable without Spring on the classpath.

**Rules** (`libs/arch-test`) turn those declarations into checks: layering, naming, event-contract
consistency, boundaries, idempotency, transaction placement. `Layer.mayDependOn` in the kernel is
the single definition of the dependency rule, and the rule asks it rather than restating it.

**Documents** (`docs/`) explain why. Three checks stop them drifting: every referenced path must
resolve, every claimed rule must exist, and every `@UseCase(id)`, `@ImplementsPrinciple` and `@Adr`
in code must resolve to a document.

## Event semantics, declared once

The recurring failure in event-driven systems is that the decisions consumers depend on — the key,
whether history survives, how many times a message may arrive — live in broker configuration, far
from the code. Here they live on the event:

```java
@EventContract(
        stream = "orders.order-placed",
        partitionKey = "orderId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/orders.order-placed.v1.json")
public record OrderPlaced(String orderId, ..., Instant occurredAt) implements DomainEvent {}
```

`libs/messaging-support` translates that into Kafka topic configuration or RabbitMQ stream
arguments — and refuses at startup when a broker cannot honour it, rather than degrading quietly.
The mapping is in
[`.claude/skills/events/references/broker-mapping.md`](.claude/skills/events/references/broker-mapping.md).

## Stack

Java 21 · Maven · Spring Boot **4.1.1** · Spring Framework 7 · Jakarta EE 11 · JUnit Jupiter 6 ·
Jackson 3 (`tools.jackson`) · Testcontainers 2 · Spring Modulith 2.1 · ArchUnit 1.4 · Flyway 12

**No Spring Cloud** — its current release train targets Boot 4.0, not 4.1. The replacements are
in [ADR-0004](docs/adr/0004-do-not-adopt-spring-cloud.md).

## Adopting it

`com.acme` is a placeholder. [G-090](docs/guides/G-090-adopting.md) has the rename script and the
list of what to keep or delete.

## Where to start reading

| You are | Read |
|---|---|
| a person, or an agent, about to change code | [AGENTS.md](AGENTS.md) |
| deciding where code goes | [docs/reference/layout.md](docs/reference/layout.md) |
| wondering why a rule exists | [docs/principles/](docs/principles/README.md) |
| wondering why a choice was made | [docs/adr/](docs/adr/README.md) |
