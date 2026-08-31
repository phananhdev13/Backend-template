# G-060 — Making a change observable

The question every incident starts with is "what happened to this one request?". The full
conventions are in the `observability` skill.

## Correlation

`CorrelationIdFilter` takes `X-Correlation-Id` or mints one, puts it in the SLF4J MDC as
`correlationId`, echoes it on the response, and clears it in a `finally` — request threads are
pooled, and a leaked MDC entry attributes one customer's log lines to another's request.

`messaging-support` copies it into a message header on publish and restores it on consume. If you
add a transport, carry it too, or the chain breaks exactly where the hard bugs are.

## Logging

- `private static final Logger log` — enforced by `ObservabilityRules.loggersAreConstants`.
- SLF4J only; a second facade misses the MDC.
- Placeholders, never concatenation: concatenated messages cannot be indexed.
- **Log decisions, not arrivals.** "Order rejected: credit limit exceeded" earns its line;
  "entering placeOrder" is noise that hides it.
- Never log personal data, credentials or tokens. Logs are replicated, retained, widely readable,
  and an erasure request cannot reach them.
- Log an exception once, where it is handled. Logging and rethrowing prints the same stack three
  times and hides which frame mattered.

Every `@UseCase` must declare a logger (`ObservabilityRules.useCasesEmitTheirIdentifier`), because a
use case that emits nothing leaves an incident with a request that arrived, a row that changed, and
nothing in between.

## Metrics

Tag by use case identifier and outcome. Never tag by anything unbounded — a customer or order id as
a tag creates one time series per value and will take the metrics backend down before it tells you
anything.

## Health

Liveness failing means restart me; readiness failing means stop sending traffic. A liveness probe
that checks the database restarts every instance during a database blip and turns a partial outage
into a total one.

## The test

Given one correlation id, can you retrieve the request, the use case decision, the event published,
and the downstream handler's outcome? Whichever hop is missing is where the next investigation
stalls.
