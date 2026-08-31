# P-060 — A request is followable end to end

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `ObservabilityRules.useCasesEmitTheirIdentifier()`, `ObservabilityRules.correlationIdCrossesEveryBoundary()` (not implemented), `ObservabilityRules.personalDataIsNeverLogged()` (not implemented), `checkstyle:RegexpSingleline` (System.out / printStackTrace) |
| **Annotations** | `@UseCase`, `@EventHandler`, `@EventContract(containsPersonalData)`, `@ImplementsPrinciple` |
| **Guide** | [G-060](../guides/G-060-observability.md) |

## Rule

Every request carries a correlation identifier from its entry point through every use case,
outbound call and published event. Logs are structured, carry the `@UseCase` id, and never
contain personal data.

## Why

**Without a correlation identifier, an incident is archaeology.** A customer reports that an
order was charged but never confirmed. The evidence is spread across the API's access log,
the order service's application log, the payment adapter's client log, and the consumer log
of whichever pod happened to own that partition — four systems with four clocks and no
shared key. Reconstructing one request from timestamps takes hours and is often impossible
once two customers did similar things in the same second. With one identifier that crosses
every hop, it is a single query.

**Asynchronous hops are where the thread is dropped.** HTTP propagation is handled by the
framework and mostly works. The event boundary is not: publishing an event on a thread pool,
or consuming one, starts a fresh context, so the trace ends at the outbox and a new
unrelated one begins in the handler. That is exactly the boundary where the hard bugs live
([P-072](P-072-transactional-outbox.md)), which is why `EventEnvelope` carries
`correlationId` as a field rather than relying on ambient context.

**The use case identifier is what makes telemetry answerable in business terms.** A metric
tagged only by HTTP route says `POST /api/v1/orders` got slower. A metric tagged
`useCase=UC-ORD-001` points at a document that says what that operation is meant to do and
which ports it uses ([P-030](P-030-use-case-unit-of-application-logic.md)). The route
changes with the API version; the identifier does not.

**Unstructured logs cannot be queried under pressure.**
`log.info("Order " + id + " placed for " + customer)` produces a string that a log platform
can grep but not aggregate: you cannot ask "p99 by customer tier" or "how many of these in
the last hour by SKU" without writing a parser at 3 a.m. Key-value pairs cost the same to
write and are queryable from the first line.

**Personal data in logs is the leak that outlives the incident.** Log storage is typically
retained for a year, replicated to a search cluster, and outside every erasure process the
service implements. An email address logged once is present in backups long after the
account is deleted. `containsPersonalData` on an event contract is what lets the logging
infrastructure redact automatically rather than relying on each author remembering.

**`System.out` is invisible.** Console output bypasses the appender, so it has no
structure, no correlation id and no retention — and in a container it competes for the same
stdout the platform is parsing as JSON, corrupting the lines around it. Checkstyle rejects
it outright.

## In code

Structured, correlated, and identified:

```java
@UseCase(id = "UC-ORD-001", value = "A customer places an order")
public final class PlaceOrderUseCase implements PlaceOrder {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderUseCase.class);

    @Override
    @Transactional
    @Observed(name = "usecase.place-order", contextualName = "UC-ORD-001")
    public OrderId place(PlaceOrderCommand command) {
        Order order = …;
        orders.save(order);
        events.publish(order.toEvent(Instant.now()));

        log.atInfo()
           .addKeyValue("useCase", "UC-ORD-001")
           .addKeyValue("orderId", order.id().value())
           .addKeyValue("customerId", order.customerId().value())   // an opaque id, not a name
           .addKeyValue("lineCount", order.lines().size())
           .log("Order placed");
        return order.id();
    }
}
```

Carrying the thread across the asynchronous boundary explicitly:

```java
@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(value = {"P-060"}, note = "copies the correlation id into the envelope")
class KafkaEventPublisher implements EventPublisher {

    @Override
    public void publish(DomainEvent event) {
        var envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(), contract.stream(), contract.version(),
                partitionKeyOf(event), event.occurredAt(),
                MDC.get("correlationId"),                        // survives the hop
                W3CTraceContext.current(), event);
        template.send(topic, envelope.partitionKey(), envelope);
    }
}
```

Wrong — unqueryable, uncorrelated, and a data leak:

```java
System.out.println("placing order for " + request.customerEmail());       // rejected by Checkstyle
log.info("Order " + id + " placed for " + customer.email() + " " + customer.address());
```

## Enforcement

`ObservabilityRules.useCasesEmitTheirIdentifier()` fails a `@UseCase` that logs without an
`addKeyValue("useCase", …)` matching its own `id()`, or that carries `@Observed` with a
`contextualName` that does not:

```
com.acme.orders.ordering.application.CancelOrderUseCase logs without its use case
identifier. Add .addKeyValue("useCase", "UC-ORD-002") so telemetry joins to
docs/use-cases/UC-ORD-002-cancel-order.md
See docs/principles/P-060-observability.md
```

`ObservabilityRules.correlationIdCrossesEveryBoundary()` (not implemented) fails an `@OutboundAdapter` of kind
`MESSAGING` that constructs an `EventEnvelope` with a null `correlationId`, and fails an
`@EventHandler` that does not restore it before calling its port.

`ObservabilityRules.personalDataIsNeverLogged()` (not implemented) fails a log statement whose key matches the
configured personal-data key list (`email`, `phone`, `address`, `pan`, `dob`, `name`), and
fails a `@EventContract(containsPersonalData = true)` payload logged whole.

Checkstyle rejects `System.out`/`System.err` and `printStackTrace()` with pointers back to
this document.

## Deviating

High-cardinality debug logging behind a sampled flag is fine; keep it at `DEBUG` and never
add unbounded values as *metric* tags — a `customerId` tag is a cardinality explosion that
will take out the metrics backend before it helps anyone.

Where a regulator requires an identifier that is personal data in an audit log, that log is
a separate sink with its own retention and erasure process, declared in an `@Adr` — not the
application log.
