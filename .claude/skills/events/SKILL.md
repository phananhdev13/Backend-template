---
name: events
description: Design, publish or consume domain events in this repo - choosing the partition key, retention and compaction, delivery guarantee and ordering; declaring them with @EventContract; making handlers idempotent; publishing transactionally via the outbox. Use whenever a change involves Kafka, RabbitMQ, an event, a message, a listener, a topic or a stream, or when an ArchUnit EventContractRules failure needs interpreting.
---

# Events

The decisions that matter about a stream - what the key is, whether history survives, how many
times a consumer may see a message - are consumer-visible contracts. In most systems they live in
broker configuration, far from the code that depends on them, and drift: someone recreates the
topic without compaction, or with a different key, and nothing notices until the data is wrong.

Here they live on the event type, in `@EventContract`. `libs/messaging-support` translates the
declaration into Kafka topic configuration or RabbitMQ stream arguments, and `EventContractRules`
rejects combinations that cannot hold. You declare intent; you never configure a topic by hand.

## Design the contract, in this order

### 1. Is the message a fact or a state snapshot?

This one choice constrains everything after it.

- **`FACT`** — something happened. `OrderPlaced`, `PaymentCaptured`. Meaningful only in sequence
  with its neighbours. **Cannot be compacted.**
- **`STATE_SNAPSHOT`** — the complete current state of an entity. `CustomerProfileChanged` carrying
  the whole profile. Self-contained, so a consumer that sees only the newest per key is correct.

Most domain events are facts. Reach for a snapshot when the stream is really a replicated table.

### 2. What is the partition key?

The aggregate identifier, nearly always. It decides partitioning, ordering and - on a compacted
stream - identity. Name the record component:

```java
@EventContract(stream = "orders.order-placed", partitionKey = "orderId", …)
public record OrderPlaced(String orderId, Money total, Instant occurredAt) implements DomainEvent {}
```

`EventContractRules.partitionKeyExistsOnRecord` checks the component exists. A key naming a field
that was renamed degrades to round-robin partitioning and takes per-key ordering with it, silently.

### 3. How long does it live?

| Retention | Use for | Never for |
|---|---|---|
| `TIME_WINDOW` (default) | facts | anything a consumer may need to replay from zero |
| `COMPACTED` | state snapshots keyed by entity | facts — compaction **deletes** superseded messages |
| `COMPACTED_AND_WINDOWED` | a rebuildable cache | a system of record |
| `INFINITE` | the system of record | anything without an owner for the storage bill |

`retentionDays` is a recovery budget, not a storage setting: a consumer that falls further behind
than the window has lost data.

### 4. Delivery and ordering

`AT_LEAST_ONCE` and `PER_KEY` are the defaults and are almost always right. `AT_LEAST_ONCE` means
handlers must be idempotent - the build enforces it. `GLOBAL` ordering forces a single partition,
caps throughput at one consumer, and needs an `@Adr`.

## Publishing

Publish through `ApplicationEventPublisher`, not to a broker. Spring Modulith's event publication
registry writes the pending publication in the **same transaction** as the state change, and
delivers after commit. Without that, a broker send between a save and a commit produces an event
for a state change that then rolls back.

```java
// in the use case
events.publishEvent(new OrderPlaced(order.id().value(), order.total(), Instant.now(clock)));
```

```java
// adapter/out/messaging - forwards after commit, retried by the registry until it succeeds
@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(value = "P-051", note = "Producer timeout and retries set in application.yml")
public class OrderEventPublisherAdapter {

    @ApplicationModuleListener
    public void on(OrderPlaced event) {
        publisher.publish(event);
    }
}
```

See [P-072](../../../docs/principles/P-072-transactional-outbox.md).

## Consuming

`@EventHandler` and `@Idempotent` state intent; they wire nothing by themselves. The class still
needs a real `@KafkaListener` method, or nothing ever calls it - that gap is easy to miss because
the class compiles and the architecture test passes either way. `messagingSupport` supplies the
serializer, the dead-letter routing and a `ProcessedMessageStore` bean; this is the shape that uses
them:

```java
@EventHandler(consumes = OrderPlaced.class, group = "billing")
@Idempotent(key = "orderId", note = "Invoice creation is an upsert keyed by order id")
@InboundAdapter(AdapterKind.MESSAGING)
public class OrderPlacedListener {

    private final ProcessedMessageStore processed;
    private final CreateInvoiceUseCase createInvoice;   // an @InputPort, called like any other

    OrderPlacedListener(ProcessedMessageStore processed, CreateInvoiceUseCase createInvoice) {
        this.processed = processed;
        this.createInvoice = createInvoice;
    }

    // The SpEL expression asks the contract for its own physical name rather than hard-coding a
    // topic string, so a stream-prefix or version change needs no edit here. Split across two
    // string-literal constants only to fit the repo's 120-column limit - @KafkaListener.topics
    // needs a compile-time constant, and javac folds literal concatenation into one.
    private static final String ORDER_PLACED_TOPIC_EXPRESSION = "#{@contractRegistry.byStream("
            + "'orders.order-placed', 1).orElseThrow().physicalName('${acme.messaging.stream-prefix:}')}";

    @KafkaListener(topics = ORDER_PLACED_TOPIC_EXPRESSION, groupId = "billing")
    @Transactional
    public void on(OrderPlaced event) {
        // Mark-then-act, in one transaction: a rebalance after marking but before acting would
        // otherwise lose the work, and acting-then-marking can double-charge on a crash between
        // the two. See ProcessedMessageStore.markProcessed for why insert-and-catch, not read-then-write.
        boolean firstDelivery = processed.markProcessed("billing", event.orderId(), Duration.ofDays(7));
        if (firstDelivery) {
            createInvoice.createInvoice(new CreateInvoiceCommand(event.orderId(), event.totalAmount()));
        }
    }
}
```

`@Idempotent` is mandatory on any handler of a stream that may redeliver
(`EventContractRules.atLeastOnceHandlersAreIdempotent`) - but it is documentation, not enforcement;
the `note` promises a specific mechanism, and the method body above is what actually keeps that
promise. Deduplication in a store is a bound on storage, not a correctness argument - a replay can
arrive months later. Where correctness must hold indefinitely, make the write itself idempotent: an
upsert keyed by the business identifier, or a state transition that is a no-op from its own target
state.

Consumer groups: one group means the work is shared; different groups mean everyone gets every
message. Getting this wrong is how one email is sent per instance.

A message that fails to process retries with a fixed backoff and lands on `<topic>.dlq` after
`acme.messaging.kafka.max-delivery-attempts` tries - configured once in `messagingSupport`, not per
listener. See
[references/broker-mapping.md](references/broker-mapping.md#failure-handling).

For a complete, real listener rather than this illustration, read
`services/agent-factory/.../adapter/in/messaging/AgentActivationAuditListener.java` - it consumes
`AgentVersionActivated`, checks `ProcessedMessageStore` before writing an audit row, and is the
class that proved this whole consuming section actually works rather than merely compiling.

## Changing a contract

Adding an optional field is compatible. **Everything else is a new `version` on a new stream**:
removing a field, changing a type, changing the key, changing retention. Run both in parallel, and
delete the old one when it has no consumers - not when the new one works.

The schema in `contracts/events/` is the artefact consumers in other repositories generate from.
`EventContractRules.everyContractHasASchemaFile` checks it exists.

## How the declaration reaches the broker

Full mapping, including the exact Kafka and RabbitMQ settings each option produces and where the
two brokers differ irreconcilably:
[references/broker-mapping.md](references/broker-mapping.md).

The short version: `messaging-support` provisions topics and streams from the contracts it finds.
RabbitMQ streams have **no compaction**, so a compacted contract on a Rabbit deployment fails at
startup rather than degrading quietly to age-based retention.

## Checklist

- [ ] `payload` is honest: `FACT` unless the message carries whole current state
- [ ] `partitionKey` names a real component, and is the aggregate identifier
- [ ] retention matches the payload kind
- [ ] schema file exists under `contracts/events/` and matches the record
- [ ] handlers of at-least-once streams are `@Idempotent` with a key stable across retries
- [ ] published from a use case via `ApplicationEventPublisher`, never straight to a broker
- [ ] a dead-letter destination exists and someone looks at it
- [ ] `INFINITE`, `GLOBAL` or compacted personal data carries an `@Adr`
