# P-042 — Event handlers are adapters with a delivery contract

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `EventContractRules.atLeastOnceHandlersAreIdempotent()`, `EventContractRules.handlersRelyingOnOrderConsumeOrderedStreams()` (not implemented), `AdapterRules.inboundAdaptersOnlyCallInputPorts()`, `NamingRules.consumerGroupsAreUnique()` (not implemented) in `libs/arch-test` |
| **Annotations** | `@EventHandler`, `@Idempotent`, `@EventContract`, `@InputPort` |
| **Guide** | [G-030](../guides/G-030-events.md) |

## Rule

A `@EventHandler` is an inbound adapter: it deserialises an `EventEnvelope`, maps it to a
command and calls one `@InputPort`. It declares what it `consumes` and the consumer `group`
it joins, and it inherits the semantics of that event's `@EventContract` — including the
obligation to be `@Idempotent` when delivery is at-least-once.

## Why

**A handler is a controller with a worse failure model.** No caller is waiting, so a
mistake does not surface as a 500 — it surfaces as data that is quietly wrong, or as a
consumer group lagging by four hours while everything reports healthy. That makes the
declarations more important here than at the HTTP edge, not less.

**`group` is the field that causes production incidents.** Two deployments sharing a group
share the partitions; two using different groups each receive every message. Give each pod a
group derived from its hostname — an easy accident when a template interpolates
`${HOSTNAME}` — and a ten-pod deployment sends ten confirmation emails per order. Reuse
another handler's group and the two silently steal each other's messages: each processes a
subset, neither errors, and the missing half is discovered by a customer. The group is a
deployment-topology decision, so it is declared in code where review can see it, not left to
a property that differs per environment.

**Declaring `consumes` lets the build check the pair rather than each side.** Neither the
contract nor the handler is wrong on its own: an `AT_LEAST_ONCE` stream is correct, and a
non-idempotent handler is correct — the combination is the bug. Same with ordering: a handler
that applies deltas is fine, until it consumes a stream declared `OrderingGuarantee.NONE`
and applies them out of order after a partition reassignment. Only the pairing is checkable,
and only if both sides are declared.

**Handlers must not decide.** The same rule as
[P-040](P-040-inbound-adapters-translate.md), with a sharper edge: business logic in a
handler runs on a code path with no user, no synchronous error, and usually no test that
loads the broker. It is the least observed place in the system, so it is the worst place to
put a rule.

**Failure handling is the handler's, and it must be explicit.** Three outcomes exist and the
handler chooses between them: a transient failure (dependency down, timeout) that should
propagate so the broker redelivers; a permanent failure (unparseable payload, a rule that
will never pass) that should go to the dead-letter destination immediately; and success.
Catching everything and acknowledging turns a broker outage into permanent data loss with no
error anywhere. Retrying a poison message forever blocks the partition behind it and stalls
every key that shares it.

## In code

```java
package com.acme.orders.ordering.adapter.in.messaging;

@EventHandler(consumes = PaymentCaptured.class, group = "orders.payment-captured.v1")
@Idempotent(key = "eventId", retentionHours = 168,
            note = "confirmPayment is a no-op from the PAID state")
@Component
class PaymentCapturedHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCapturedHandler.class);

    private final ConfirmPayment confirmPayment;         // one @InputPort
    private final ProcessedEvents processed;             // @OutputPort — the de-duplication store

    @KafkaListener(topics = "#{@streams.physicalName('orders.payment-captured', 1)}",
                   groupId = "orders.payment-captured.v1")
    void on(EventEnvelope<PaymentCaptured> envelope) {
        if (!processed.claim(envelope.eventId(), Duration.ofDays(7))) {
            log.atDebug().addKeyValue("eventId", envelope.eventId()).log("Duplicate delivery ignored");
            return;
        }
        PaymentCaptured event = envelope.payload();
        confirmPayment.confirm(new ConfirmPaymentCommand(
                new OrderId(event.orderId()), new PaymentReference(event.reference())));
    }
}
```

Failure handling stated rather than implied:

```java
    @KafkaListener(...)
    void on(EventEnvelope<PaymentCaptured> envelope) {
        try {
            handle(envelope);
        } catch (ValidationException | BusinessRuleViolation permanent) {
            // Will never succeed on redelivery. Park it; do not block the partition.
            deadLetters.publish(envelope, permanent);
        }
        // Everything else propagates: the broker redelivers, and @Idempotent makes that safe.
    }
```

Wrong — a swallowed exception is silent data loss, and the group is per-instance:

```java
@EventHandler(consumes = PaymentCaptured.class, group = "orders-${HOSTNAME}")   // every pod gets every message
@Component
class PaymentCapturedHandler {

    @KafkaListener(topics = "orders.payment-captured")
    void on(EventEnvelope<PaymentCaptured> envelope) {
        try {
            orders.findById(...).markPaid();       // decides, and reaches past the port
        } catch (Exception ex) {
            log.warn("Failed", ex);                // acknowledged anyway: the payment is lost
        }
    }
}
```

## Enforcement

`EventContractRules.atLeastOnceHandlersAreIdempotent()` resolves `consumes()` to its
`@EventContract` and fails when `delivery()` is `AT_LEAST_ONCE` (or `EFFECTIVELY_ONCE` with a
non-broker side effect) and the handler carries no `@Idempotent`:

```
com.acme.orders.ordering.adapter.in.messaging.PaymentCapturedHandler consumes
PaymentCaptured, whose contract declares delivery = AT_LEAST_ONCE, but is not @Idempotent.
Duplicate delivery is normal, not exceptional.
See docs/principles/P-071-idempotency.md
```

`EventContractRules.handlersRelyingOnOrderConsumeOrderedStreams()` (not implemented) fails a handler annotated
`@Idempotent` with a note claiming order-dependence, or reading a version/sequence field,
when the contract declares `OrderingGuarantee.NONE`.

`NamingRules.consumerGroupsAreUnique()` (not implemented) fails two handlers declaring the same `group`, and
fails a `group` containing `${` — a group interpolated per environment or per host is a
topology bug.

`AdapterRules.inboundAdaptersOnlyCallInputPorts()` applies to handlers exactly as to
controllers.

## Deviating

A handler that fans one event into two use cases is acceptable when the second is
idempotent and independently retryable; record it with `@Adr` and say which one being
applied without the other is tolerable, because after a partial failure that is the state
you will be in.

Purely technical consumers — a metrics tap, a stream mirror — need no `@InputPort`. Mark
them `@ImplementsPrinciple("P-060")` so `TraceabilityRules` does not read them as a
violation.

Related: [P-070](P-070-event-semantics.md) for the contract they inherit,
[P-071](P-071-idempotency.md) for how to be safely repeatable.
