# P-060 — A request is followable end to end

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `ObservabilityRules.useCasesEmitTheirIdentifier()`, `ObservabilityRules.useCasesAreObserved()`, `ObservabilityRules.correlationIdCrossesEveryBoundary()` (not implemented), `ObservabilityRules.personalDataIsNeverLogged()` (not implemented), `checkstyle:RegexpSingleline` (System.out / printStackTrace) |
| **Annotations** | `@UseCase`, `@EventHandler`, `@EventContract(containsPersonalData)`, `@ImplementsPrinciple`, `@Observed`, `@WithSpan` |
| **Guide** | [G-060](../guides/G-060-observability.md) |

## Rule

Every request carries a correlation identifier from its entry point through every use case,
outbound call and published event. Every use case is metered under its own identifier and
traced under its own span name. Logs are structured, carry that identifier, and never contain
personal data.

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

**A correlation identifier and a trace are not the same guarantee, and neither replaces the
other.** `correlationId` is written by `CorrelationIdFilter` on every request, sampled or not,
and travels in `EventEnvelope` regardless of what any tracing backend decided. A trace exists
only for the fraction of requests a sampler decided to keep — production cannot afford to keep
every trace, so it does not. A dropped trace still has a correlation id in the logs and the
event envelope; a request with no correlation id is one nothing downstream can join to at all.
Building this on the correlation id, not the trace id, is what keeps every incident answerable
even on the requests sampling threw away.

**A fixed sampling rate keeps the wrong traces.** A rate that keeps 1% of requests keeps 1% of
your errors and 1% of your slow requests too — exactly as likely to be discarded as an
ordinary fast success, when they are the ones an incident actually needs. Choosing to keep
every error and every slow request regardless of the overall rate needs a decision made after
the whole trace exists, which a single service's head-based sampler cannot see far enough to
make; see Enforcement for where that decision belongs instead.

**The use case is the unit both a metric and a span should be named for.** A `Timer` and a
`Span` are two different mechanisms feeding two different systems, but both should read as
"what business operation was this", not "what URL was hit" or "what class ran" — the same
reasoning as the use case identifier in a log line, applied to the other two signals.

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

Metered, traced, correlated, and identified - the real method, from `order-service`:

```java
@UseCase(id = "UC-ORD-001", value = "A customer places an order")
@Transactional
public class PlaceOrderService implements PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    @Override
    @Observed(name = "usecase.place-order", contextualName = "UC-ORD-001")   // Micrometer: a Timer
    @WithSpan("usecase.UC-ORD-001")                                          // OTel agent: a Span
    public OrderId placeOrder(PlaceOrderCommand command) {
        Order order = Order.place(OrderId.newId(), command.customerId(), command.lines(), clock);
        orders.save(order);
        Span.current().setAttribute("orderId", order.id().value().toString());   // enrich, not create

        events.publishEvent(order.toEvent());
        log.info("UC-ORD-001 placed order orderId={} lines={}", order.id().value(), order.lines().size());
        return order.id();
    }
}
```

Two annotations, two different mechanisms, on purpose (ADR-0019):

- **`@Observed`** feeds Micrometer, this repository's metrics pipeline. It needs
  `management.observations.annotations.enabled: true` in the service's own `application.yml` -
  **off by default in Boot 4.1**, because an aspect wrapping every `@Observed` method is a cost
  not every application wants paid unconditionally; here it is worth paying, because
  `ObservabilityRules.useCasesAreObserved` requires every use case to carry it.
- **`@WithSpan`**, from `opentelemetry-instrumentation-annotations`, produces a real span only
  when the process runs under `-javaagent:opentelemetry-javaagent.jar` (ADR-0019). Without the
  agent it compiles and runs and creates nothing - silently. `Span.current()` is likewise a
  real, no-op-safe way to add an attribute the method only knows once it has run; `@SpanAttribute`
  cannot do this, since it only reads a method's *incoming* parameters and `orderId` does not
  exist until after `Order.place` returns.

Carrying the correlation id across the asynchronous boundary explicitly, because the agent's
span context and Micrometer's `Observation` both end at the outbox - only the identifier that
travels as a field survives:

```java
@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.MESSAGING)
class KafkaEventPublisher implements EventPublisher {

    void send(ProducerRecord<String, Object> record) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            header(record, HEADER_CORRELATION_ID, correlationId);   // survives the hop; see messaging-support
        }
        // …
    }
}
```

Structured JSON logging needs no library at all - Boot 4.1 ships it:

```yaml
logging:
  structured:
    format:
      console: ecs      # also: gelf, logstash
```

Confirmed by generating real output, not assumed: every MDC entry active when a line is logged
- `correlationId` today, `trace_id`/`span_id`/`trace_flags` the moment the OTel agent is
attached - appears as a top-level field automatically, with no encoder configuration:

```json
{"@timestamp":"2026-09-02T05:08:37.729Z","log":{"level":"INFO","logger":"com.acme.order.ordering.application.PlaceOrderService"},"service":{"name":"order-service"},"message":"UC-ORD-001 placed order orderId=7ae0…","correlationId":"6e8114cb-7da9-485f-bbf1-4c5836807930","ecs":{"version":"8.11"}}
```

Wrong — unqueryable, uncorrelated, and a data leak:

```java
System.out.println("placing order for " + request.customerEmail());       // rejected by Checkstyle
log.info("Order " + id + " placed for " + customer.email() + " " + customer.address());
```

## Enforcement

`ObservabilityRules.useCasesEmitTheirIdentifier()` fails a `@UseCase` with no logger field at
all - the minimum bar, that the use case can say anything happened.

`ObservabilityRules.useCasesAreObserved()` fails a `@UseCase` where no method carries
`@Observed`:

```
com.acme.orders.ordering.application.CancelOrderService is a @UseCase with no @Observed
method. Add @Observed(name = "usecase.<slug>", contextualName = "<UC id>") to the method
implementing its @InputPort, or suppress with @Adr if this use case is deliberately unmetered.
```

Neither rule can see whether the `@Observed(contextualName = …)` actually matches the class's
own `@UseCase(id = …)`, or whether a log line's key-value pairs actually name the use case -
ArchUnit's model does not reach into annotation members against each other or into a method
body's string arguments that precisely. Getting the id right is still a review question, the
same way `docs/principles/P-080-api-versioning.md` leaves "was this change additive" to a
human even after its own contract test passes.

`ObservabilityRules.correlationIdCrossesEveryBoundary()` (not implemented) fails an `@OutboundAdapter` of kind
`MESSAGING` that constructs an `EventEnvelope` with a null `correlationId`, and fails an
`@EventHandler` that does not restore it before calling its port.

`ObservabilityRules.personalDataIsNeverLogged()` (not implemented) fails a log statement whose key matches the
configured personal-data key list (`email`, `phone`, `address`, `pan`, `dob`, `name`), and
fails a `@EventContract(containsPersonalData = true)` payload logged whole.

Checkstyle rejects `System.out`/`System.err` and `printStackTrace()` with pointers back to
this document.

**Tracing itself is not enforced by any test, and cannot be**, per ADR-0019: `@WithSpan`
only produces a span when the process runs under `-javaagent:opentelemetry-javaagent.jar`,
and `mvn test` never does. A method carrying `@WithSpan` that creates nothing under test is
not a bug - it is the one thing this principle's tooling cannot see, recorded as evidence in
the ADR rather than left to surprise a reviewer.

**Sampling is deployment configuration, not code**, set on the agent at the environment it
runs in - never hand-rolled per service:

| | |
|---|---|
| `OTEL_TRACES_SAMPLER` | `parentbased_traceidratio` in production - respects a sampling decision already made upstream, then applies a fixed rate to the rest |
| `OTEL_TRACES_SAMPLER_ARG` | the rate, e.g. `0.1` for 10% of root spans |
| Errors and slow requests kept regardless of rate | not a per-service setting at all - a Collector's tail-sampling processor, which decides after the whole trace exists. A single service's head-based sampler cannot see the rest of the trace to make that call |

## Deviating

High-cardinality debug logging behind a sampled flag is fine; keep it at `DEBUG` and never
add unbounded values as *metric* tags — a `customerId` tag is a cardinality explosion that
will take out the metrics backend before it helps anyone.

A use case that is genuinely fire-and-forget internal plumbing, with no operator who would
ever look up its latency, may skip `@Observed` behind an `@Adr` - this should be rare enough
that reaching for it is itself worth a second look.

Where a regulator requires an identifier that is personal data in an audit log, that log is
a separate sink with its own retention and erasure process, declared in an `@Adr` — not the
application log.
