---
name: observability
description: Make a request followable from the HTTP edge through the use case across a broker into the next service - correlation identifiers, structured logging, metrics (@Observed) and tracing (the OpenTelemetry Java agent + @WithSpan) conventions here. Use when adding logging, when an incident could not be reconstructed from the logs, when adding a metric, a health check or a span, or when a correlation id is missing across a broker hop.
---

# Observability

The question every incident starts with is "what happened to this one request?". Everything here
exists to make that answerable without a debugger. See
[P-060](../../../docs/principles/P-060-observability.md) and
[ADR-0019](../../../docs/adr/0019-otel-javaagent-for-tracing-micrometer-for-metrics.md) for the full
reasoning; this is the checklist.

## Correlation

`CorrelationIdFilter` in `libs/observability-support` takes the `X-Correlation-Id` header or mints
one, puts it in the SLF4J MDC under `correlationId`, echoes it on the response, and clears it in a
`finally`. That last part is not fussiness: request threads are pooled, and a leaked MDC entry
attributes one customer's log lines to another's request.

Carrying it across a broker is the part that gets forgotten. `messaging-support`'s
`KafkaEventPublisher` copies `MDC.get("correlationId")` into a message header on publish and
restores it on consume, so a chain that goes HTTP → use case → event → another service stays one
searchable identifier. If you write a new transport, carry it too, or the chain breaks exactly
where the hard bugs live.

**A correlation id is not a trace id.** `correlationId` exists on every request, sampled or not.
`trace_id` (below) exists only for the fraction of requests a sampler kept. Never write code that
assumes the second implies the first.

## Logging

- One logger per class: `private static final Logger log = LoggerFactory.getLogger(X.class);`.
  `ObservabilityRules.loggersAreConstants` enforces it, because a per-instance logger is named
  unpredictably and breaks per-class filtering.
- SLF4J only. A second facade misses the MDC and is invisible to the pipeline.
  `ObservabilityRules.oneLoggingFacade` enforces it.
- Structured, with placeholders - `log.info("Order placed orderId={} total={}", id, total)` - never
  string concatenation. Concatenated messages cannot be indexed or aggregated.
- **Log decisions, not arrivals.** "Order rejected: credit limit exceeded" is worth an entry.
  "Entering placeOrder" is noise that makes the useful lines harder to find.
- Never log personal data, credentials, tokens or full payment details. Logs are replicated,
  retained and widely readable, and an erasure request cannot reach them.
- Log an exception once, where it is handled, with context. Logging and rethrowing produces the
  same stack three times and hides which frame mattered.

Levels: `ERROR` needs a human tonight; `WARN` needs a human eventually; `INFO` is a business event
worth keeping; `DEBUG` is for reproducing locally.

**Structured JSON output needs no library** - Boot 4.1 ships it natively:

```yaml
logging:
  structured:
    format:
      console: ecs      # also: gelf, logstash
```

Confirmed by generating real output: every MDC entry active when a line is logged appears as a
top-level JSON field automatically, with zero encoder configuration - `correlationId` today,
`trace_id`/`span_id`/`trace_flags` the moment the OpenTelemetry agent (below) is attached to the
process. This is the entire mechanism that joins a log line to a trace; there is nothing else to
wire.

## Metrics

Micrometer, via Actuator, stays this repository's metrics pipeline - not the OpenTelemetry agent's
own metrics exporter (`OTEL_METRICS_EXPORTER=none` when the agent is attached; see ADR-0019).
Instrument what a user would notice: use case latency and failure rate, queue lag, dependency call
latency and error rate, pool saturation.

**`@Observed` is how a use case gets a `Timer` tagged by its own identifier, without a hand-written
metric in every use case:**

```java
@Override
@Observed(name = "usecase.place-order", contextualName = "UC-ORD-001")
public OrderId placeOrder(PlaceOrderCommand command) { … }
```

It does nothing until the consuming service turns it on - **`@Observed` is a real no-op without
this**, confirmed by generating a context with and without the property:

```yaml
management:
  observations:
    annotations:
      enabled: true
```

Off by Boot 4.1's own default, because AOP-proxying every `@Observed` method is a cost not every
application wants paid unconditionally. `ObservabilityRules.useCasesAreObserved` requires every
`@UseCase` to carry it, so every service in this repository turns the property on; check
`services/*/src/main/resources/application.yml` if a use case's metric is silently missing before
suspecting the annotation.

Tag by use case identifier and outcome, never by anything unbounded - a customer id or an order id
as a tag creates one time series per value and will take down the metrics backend before it tells
you anything.

## Tracing

**The OpenTelemetry Java agent** (`-javaagent:opentelemetry-javaagent.jar`, zero application code)
auto-instruments HTTP, JDBC, Kafka, RabbitMQ and gRPC by bytecode weaving at class-load time. This
is the platform's chosen mechanism for traces - never `spring-boot-starter-opentelemetry` and never
a hand-wired Micrometer Tracing bridge; see ADR-0019 for why, and for the alternatives it rejected.

Custom, business-named spans use `@WithSpan("usecase.<ID>")` from
`opentelemetry-instrumentation-annotations`, already a dependency wherever `observability-support`
is:

```java
@WithSpan("usecase.UC-ORD-001")
public OrderId placeOrder(PlaceOrderCommand command) {
    Order order = …;
    Span.current().setAttribute("orderId", order.id().value().toString());   // enrich after the fact
    …
}
```

**Confirmed by running a real process under the real agent jar, not assumed:**

- `@WithSpan` produces a span, and `@SpanAttribute` on a parameter appears as a span attribute -
  but *only* when the process is launched with `-javaagent:`. Under plain `mvn test`, the
  annotation compiles and runs and creates nothing, silently. A method carrying `@WithSpan` with no
  span in a test run is not a bug; there is no test in this repository that can prove a span was
  created, and there cannot be one.
- `@SpanAttribute` only reads a method's *incoming* parameters. An id generated inside the method
  (an order id assigned by `Order.place`) cannot be attached that way - use `Span.current()`
  instead, once the value exists, as in `PlaceOrderService`.
- The agent auto-populates the SLF4J MDC with `trace_id`, `span_id` and `trace_flags` whenever a
  span is active, with **no code change** - this is the entire link between a log line and a
  trace; see Logging, above.

Span names are the use case identifier, so a trace reads as a sequence of business steps rather
than a sequence of URLs - the same reasoning as the log line's `useCase` field.

### Sampling in production

Production cannot keep every trace; that is what sampling is for, and it is deployment
configuration, never code:

```
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.1        # 10% of root spans
```

**A fixed rate keeps the wrong traces.** A 10% sample keeps 10% of your errors and 10% of your
slow requests too - exactly as likely to be dropped as an ordinary fast success, when they are the
ones an incident needs. Keeping every error and every slow request regardless of the overall rate
is not something one service's head-based sampler can decide; it needs to see the whole trace
after the fact, which is what an OpenTelemetry Collector's **tail-sampling processor** is for,
deployed once, independently of any service's release. Head-based sampling at the agent stays the
default; reach for a Collector when production needs errors and slow requests kept regardless of
rate, not before.

## Health

Actuator exposes liveness and readiness by default in Boot 4. Keep the distinction honest:
**liveness** failing means restart me; **readiness** failing means stop sending traffic. A liveness
probe that checks a database will restart every instance during a database blip and turn a partial
outage into a total one.

## The test

Before calling an incident-facing change done: given one correlation id, can you retrieve the
request, the use case decision, the event published, and the downstream handler's outcome? Given a
production trace id (when one was sampled), does it read as the same sequence of use case names as
the logs? If any hop is missing, that hop is where the next investigation will stall.
