# P-072 — State changes and their events commit together

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `OutboxRules.noBrokerCallInsideATransaction()` in `libs/arch-test`. `OutboxRules.eventIdIsAssignedInTheTransaction()` (not implemented) |
| **Annotations** | `@UseCase`, `@OutputPort`, `@OutboundAdapter`, `@ImplementsPrinciple`, `@EventContract` |
| **Guide** | [G-030](../guides/G-030-events.md) |

## Rule

An event is written to the outbox in the same database transaction as the state change it
describes, and relayed to the broker afterwards by a separate process. Never call the broker
inside a transaction, and never publish after commit from application code.

## Why

**A database and a broker cannot be committed atomically, so ordering the two writes is the
only lever you have — and both orderings fail differently.**

*Broker first, then commit.* The event is published, then the transaction rolls back — a
duplicate key, a serialisation failure, a timeout on a later statement. Downstream now
believes an order exists that the database says never happened. The warehouse picks stock, the
customer receives a confirmation email, and support has an order id that returns 404. This is
the worst class of distributed bug: no error is logged anywhere, because from each
component's point of view nothing went wrong. It reproduces only under the concurrency that
caused the rollback, so it survives every test run.

*Commit, then broker.* The transaction commits, then the process is killed — a deploy, an OOM
kill, a node drain — before the send. The order exists and no downstream system ever hears
about it. Payment is never captured, the shipment is never created, and nothing retries
because the code path that would have published is gone. Silent under-delivery, discovered by
reconciliation weeks later, if there is a reconciliation.

The outbox removes the choice: the event row and the state change share one transaction, so
they are atomically both-or-neither. Delivery then becomes a separate problem with a
well-understood solution — a relay that reads committed rows and retries until the broker
acknowledges. That converts "may be lost" into "will arrive, possibly more than once", which
is exactly the guarantee [P-071](P-071-idempotency.md) is built to absorb.

**Calling the broker inside a transaction is bad even when it works.** A `send().get()` holds
the database transaction open for the round trip, so row locks are held for broker latency
rather than database latency. When the broker degrades from 5 ms to 500 ms, connection pool
exhaustion and lock timeouts appear on the database — the symptom points at the wrong
component, and the first hour of the incident is spent looking at Postgres.

**Assigning `eventId` inside the transaction is what makes downstream de-duplication work.**
The relay may send the same row twice; if the id were generated per send attempt, each copy
would look like a new event and consumers could not de-duplicate at all
([P-071](P-071-idempotency.md)). Generated once, with the row, it is stable across every
retry forever.

**Ordering survives because the outbox is ordered per key.** The relay sends rows for one
partition key in insertion order, so per-key ordering promised by the contract
([P-070](P-070-event-semantics.md)) holds end to end rather than only inside the broker.

**Spring Modulith's event publication registry is an outbox.** It persists an incomplete
publication in the same transaction as the listener's trigger and republishes on restart.
Using it, or a hand-rolled outbox table, is a choice about operational visibility and replay
tooling — not about whether to have one.

## In code

The use case publishes through a port; it does not know a broker exists:

```java
@UseCase(id = "UC-ORD-001", value = "A customer places an order")
@ImplementsPrinciple(value = {"P-072"}, note = "OrderPlaced is written to the outbox in this transaction")
public final class PlaceOrderUseCase implements PlaceOrder {

    @Override
    @Transactional
    public OrderId place(PlaceOrderCommand command) {
        Order order = Order.place(OrderId.newId(), profile.id(), lines, profile.creditLimit());
        orders.save(order);
        events.publish(order.toEvent(Instant.now()));   // an INSERT, not a network call
        return order.id();
    }
}
```

The adapter writes a row. Note the id and the key are assigned here, inside the transaction:

```java
package com.acme.orders.ordering.adapter.out.messaging;

@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.PERSISTENCE)
@ImplementsPrinciple(value = {"P-072", "P-060"}, note = "outbox writer; carries the correlation id")
class OutboxEventPublisher implements EventPublisher {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;              // tools.jackson.databind.ObjectMapper

    @Override
    public void publish(DomainEvent event) {
        EventContract contract = Contracts.of(event.getClass());
        jdbc.sql("""
                INSERT INTO outbox (event_id, stream, version, partition_key, occurred_at,
                                    correlation_id, payload, created_at)
                VALUES (:eventId, :stream, :version, :key, :occurredAt, :correlationId, :payload, now())
                """)
            .param("eventId", UUID.randomUUID().toString())      // assigned once, with the row
            .param("stream", contract.stream())
            .param("version", contract.version())
            .param("key", Contracts.partitionKeyOf(event))
            .param("occurredAt", event.occurredAt())
            .param("correlationId", MDC.get("correlationId"))
            .param("payload", mapper.writeValueAsString(event))
            .update();
    }
}
```

The relay runs outside any business transaction, retries, and marks rows sent:

```java
@InboundAdapter(AdapterKind.SCHEDULER)
@ImplementsPrinciple(value = {"P-072"}, note = "drains the outbox; at-least-once by design")
@Component
class OutboxRelay {

    @Scheduled(fixedDelayString = "PT0.5S")
    void drain() {
        for (OutboxRow row : outbox.claimBatch(100)) {          // SELECT ... FOR UPDATE SKIP LOCKED
            template.send(physicalName(row.stream(), row.version()), row.partitionKey(), row.toEnvelope())
                    .thenAccept(result -> outbox.markSent(row.eventId()));
        }
    }
}
```

Wrong — the failure at the top of this document, in two lines:

```java
@Transactional
public OrderId place(PlaceOrderCommand command) {
    Order order = …;
    kafkaTemplate.send("orders.order-placed", order.id().toString(), event).get();  // broker inside the tx
    orders.save(order);                                                              // may still roll back
    return order.id();
}
```

## Enforcement

`OutboxRules.noBrokerCallInsideATransaction()` fails any class in `..application..` or
`..domain..` that depends on a type which sends: `KafkaTemplate`, `KafkaOperations`,
`RabbitTemplate`, `AmqpTemplate`, or this repo's own `EventPublisher` and `TaskPublisher`.

It is scoped to the layer rather than to `@UseCase`, because keying on the annotation let a use
case delegate the send to a plain collaborator beside it — the likeliest real shape — and pass.
`StreamBridge` is not in the list: that is Spring Cloud Stream, which
[ADR-0004](../adr/0004-do-not-adopt-spring-cloud.md) rules out, so naming it would describe a
dependency this repository cannot have.

The types are named individually rather than by package, and that matters in both directions: a
ban on `org.springframework.kafka..` would also catch `@KafkaListener` on a *consumer*, where
receiving inside a transaction is the correct mark-then-act shape
([P-071](P-071-idempotency.md)).

**The relay is not caught by this, and must not be.** `OrderEventPublisherAdapter` is an
`@OutboundAdapter(kind = MESSAGING)` that genuinely talks to the broker — that is its job. It
runs on `@ApplicationModuleListener`, after the commit, which is the second half of the outbox.
An earlier version of this document claimed the rule failed exactly that adapter for being
`MESSAGING` rather than `PERSISTENCE`; it does not, and it should not, because Modulith's
publication registry is the outbox table and the relay is the process that drains it.

`OutboxRules.eventIdIsAssignedInTheTransaction()` (not implemented) fails a relay that generates an event id
per send attempt, which would defeat consumer de-duplication.

## Deviating

Where the broker *is* the system of record and no database write accompanies the event — a
pure ingest gateway, a telemetry tap — there is nothing to make atomic, and a direct send is
correct. Mark it `@ImplementsPrinciple("P-070")` with a note, so it does not read as an
oversight.

Change data capture (Debezium and similar) is an outbox with a different relay: the atomicity
argument is unchanged, the operational trade-offs are not. It needs an `@Adr` covering
schema-change handling and replay.

A synchronous call to another service inside the transaction has all the same problems and
none of the outbox's mitigations. Publish an event and let the other side converge
([P-020](P-020-aggregate-consistency-boundaries.md)).
