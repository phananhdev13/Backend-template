---
name: observability
description: Make a request followable from the HTTP edge through the use case across a broker into the next service - correlation identifiers, structured logging, metrics and tracing conventions here. Use when adding logging, when an incident could not be reconstructed from the logs, when adding a metric or a health check, or when a correlation id is missing across a broker hop.
---

# Observability

The question every incident starts with is "what happened to this one request?". Everything here
exists to make that answerable without a debugger.

## Correlation

`CorrelationIdFilter` in `libs/observability-support` takes the `X-Correlation-Id` header or mints
one, puts it in the SLF4J MDC under `correlationId`, echoes it on the response, and clears it in a
`finally`. That last part is not fussiness: request threads are pooled, and a leaked MDC entry
attributes one customer's log lines to another's request.

Carrying it across a broker is the part that gets forgotten. `messaging-support` copies the
correlation id into a message header on publish and restores it on consume, so a chain that goes
HTTP → use case → event → another service stays one searchable identifier. If you write a new
transport, carry it too, or the chain breaks exactly where the hard bugs live.

## Logging

- One logger per class: `private static final Logger log = LoggerFactory.getLogger(X.class);`.
  `ObservabilityRules.loggersAreConstants` enforces it, because a per-instance logger is named
  unpredictably and breaks per-class filtering.
- SLF4J only. A second facade misses the MDC and is invisible to the pipeline.
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

## Metrics

Micrometer, via Actuator. Instrument what a user would notice: use case latency and failure rate,
queue lag, dependency call latency and error rate, pool saturation.

Tag by use case identifier and outcome, never by anything unbounded - a customer id or an order id
as a tag creates one time series per value and will take down the metrics backend before it tells
you anything.

## Tracing

Micrometer Tracing propagates W3C trace context automatically over HTTP. Span names should be the
use case identifier, so a trace reads as a sequence of business steps rather than of URLs. Sample
aggressively in production and keep errors at 100%.

## Health

Actuator exposes liveness and readiness by default in Boot 4. Keep the distinction honest:
**liveness** failing means restart me; **readiness** failing means stop sending traffic. A liveness
probe that checks a database will restart every instance during a database blip and turn a partial
outage into a total one.

## The test

Before calling an incident-facing change done: given one correlation id, can you retrieve the
request, the use case decision, the event published, and the downstream handler's outcome? If any
hop is missing, that hop is where the next investigation will stall.
