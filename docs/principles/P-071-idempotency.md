# P-071 — At-least-once delivery makes idempotency mandatory

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `EventContractRules.atLeastOnceHandlersAreIdempotent()`, `EventContractRules.idempotencyKeyIsStableAcrossRetries()`, `ResilienceRules.retriesOnlyOnIdempotentOperations()` (not implemented) in `libs/arch-test` |
| **Annotations** | `@Idempotent`, `@EventHandler`, `@EventContract` |
| **Guide** | [G-030](../guides/G-030-events.md) |

## Rule

Any handler of an `AT_LEAST_ONCE` stream carries `@Idempotent` naming a key that is stable
across retries of the same logical event. Handling a message twice must have the same effect
as handling it once — preferably because the write itself is idempotent, not because a
de-duplication table happened to still hold the record.

## Why

**Duplicates are normal operation, not an incident.** They arrive from at least four
routine sources: a consumer that processed a batch and died before committing offsets; a
rebalance triggered by a rolling deploy, where the partition moves to another pod after work
was done but before the commit; a producer retry after an ambiguous send; and a deliberate
replay to recover from a bad deploy. None of these are failures of the broker. They are the
cost of never losing a message, which is the trade you want.

**The concrete shape: a rebalance double-charges a customer.** A pod consumes
`PaymentAuthorised` for order 4711, calls the payment provider, captures £240, and is killed
by the deploy's `SIGTERM` before its offset commit lands. Kafka reassigns the partition; the
new consumer starts from the last committed offset, which is before 4711; it captures £240
again. Two charges, no error anywhere, and the only alert is the customer's. This scenario
requires nothing unusual — just a deploy at the wrong moment — and it recurs on every deploy
until the handler is made idempotent.

**A de-duplication window is a storage bound, not a correctness argument.** `retentionHours
= 168` means duplicates arriving inside a week are caught. A replay from the beginning of a
30-day stream, run to recover from a corrupted projection, is outside that window and every
message looks new. This is why `@Idempotent.note` exists: state *why* repeating is safe, and
prefer a design where the answer does not depend on a timer.

**The strongest form is an idempotent write.** An upsert keyed by the business identifier, or
a state transition that is a no-op from its own target state, is safe no matter when the
duplicate arrives — a year later, after the de-duplication table has been truncated, during a
full replay. `order.markPaid()` that returns unchanged when already `PAID` needs no
bookkeeping at all. Reach for the de-duplication store only when the effect is genuinely not
repeatable, such as calling a third party that has no idempotency key of its own.

**The key must be stable, and this is where it usually goes wrong.** `UUID.randomUUID()`
generated at publish time de-duplicates nothing: a producer retry generates a new id, so the
second copy is a new event as far as the consumer can tell. The key must be derived from the
logical event — `eventId` assigned once when the event was created and persisted with it, or
a business identifier such as `orderId` when one event per order is the invariant. An
`EventEnvelope.eventId` populated inside the outbox transaction
([P-072](P-072-transactional-outbox.md)) is stable by construction; one generated in the
publisher is not.

**Claiming must be atomic with the effect, or the window reopens.** Check-then-act — read the
de-duplication table, then do the work, then insert — has a race between two consumers of the
same partition during a rebalance, which is exactly when duplicates occur. Insert first with
a unique constraint, in the same transaction as the effect, so the database arbitrates.

**`EFFECTIVELY_ONCE` stops at the broker boundary.** Kafka transactions and an idempotent
producer give exactly-once *within* Kafka. The moment a handler writes to a database or
calls another service, the guarantee is the handler's to keep — so a handler with an external
side effect still needs `@Idempotent` even on an `EFFECTIVELY_ONCE` stream.

## In code

Strongest form — the write is idempotent, so nothing is stored to make it so:

```java
@EventHandler(consumes = PaymentCaptured.class, group = "orders.payment-captured.v1")
@Idempotent(key = "eventId",
            note = "confirmPayment transitions PLACED -> PAID and is a no-op from PAID; "
                 + "safe on replay regardless of retention")
@Component
class PaymentCapturedHandler {

    private final ConfirmPayment confirmPayment;

    @KafkaListener(topics = "#{@streams.physicalName('orders.payment-captured', 1)}",
                   groupId = "orders.payment-captured.v1")
    void on(EventEnvelope<PaymentCaptured> envelope) {
        confirmPayment.confirm(new ConfirmPaymentCommand(
                new OrderId(envelope.payload().orderId()),
                new PaymentReference(envelope.payload().reference())));
    }
}
```

```java
@AggregateRoot
public final class Order {
    public Order confirmPayment(PaymentReference reference, Instant at) {
        if (status == OrderStatus.PAID) {
            return this;                     // idempotent by construction
        }
        …
    }
}
```

Where the effect is genuinely not repeatable, claim atomically in the same transaction:

```java
@UseCase(id = "UC-ORD-007", value = "A captured payment is confirmed against its order")
public final class ConfirmPaymentUseCase implements ConfirmPayment {

    @Override
    @Transactional
    public void confirm(ConfirmPaymentCommand command) {
        if (!processed.claim(command.eventId())) {   // INSERT ... ON CONFLICT DO NOTHING, same tx
            return;                                   // already applied; rolling back nothing
        }
        Order order = orders.findById(command.orderId())
                .orElseThrow(() -> NotFoundException.of("Order", command.orderId()));
        orders.save(order.confirmPayment(command.reference(), Instant.now()));
        receipts.email(order.customerId());           // not repeatable — hence the claim
    }
}
```

Wrong — a fresh key per publish attempt, and a race between check and act:

```java
@EventHandler(consumes = PaymentCaptured.class, group = "orders.payment-captured.v1")
@Idempotent(key = "deliveryId")                        // generated per send: de-duplicates nothing
class PaymentCapturedHandler {

    void on(EventEnvelope<PaymentCaptured> envelope) {
        if (processed.contains(envelope.eventId())) return;    // check …
        payments.capture(…);                                    // … act …
        processed.record(envelope.eventId());                   // … record. Two consumers both pass the check.
    }
}
```

## Enforcement

`EventContractRules.atLeastOnceHandlersAreIdempotent()` pairs the handler with the contract
of the type it consumes:

```
com.acme.orders.ordering.adapter.in.messaging.PaymentCapturedHandler consumes
PaymentCaptured, whose @EventContract declares delivery = AT_LEAST_ONCE, but the handler
is not @Idempotent. A consumer rebalance mid-batch will redeliver this message.
See docs/principles/P-071-idempotency.md
```

`EventContractRules.idempotencyKeyIsStableAcrossRetries()` resolves `@Idempotent.key()`
against the event record's components and `EventEnvelope`'s fields, and fails a key that
matches neither, or one whose producing code path calls `UUID.randomUUID()` at publish time
rather than at event construction.

`ResilienceRules.retriesOnlyOnIdempotentOperations()` (not implemented) applies the same reasoning to outbound
HTTP: a `@Retryable` `POST` without an idempotency key fails
([P-051](P-051-remote-call-resilience.md)).

## Deviating

`AT_MOST_ONCE` streams need no idempotency, because there is no redelivery — and no
durability. That is acceptable for telemetry summarised elsewhere and for nothing that moves
money or state.

A handler that is idempotent only within a bounded window — because the effect is a
notification, and a duplicate a year later is merely embarrassing — is fine. Say so in
`note`, and set `retentionHours` to a value the operations team can actually store.
