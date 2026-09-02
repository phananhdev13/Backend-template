# ADR-0019 — The OpenTelemetry Java agent for tracing; Micrometer stays the metrics pipeline

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-02 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

`libs/observability-support` already carries a correlation identifier across every hop
(`CorrelationIdFilter`, `Correlation`) and depends on `io.micrometer:micrometer-tracing` - but
with no bridge and no exporter configured, that dependency currently supplies only the no-op
tracer; nothing this repository ships today actually produces a trace. P-060 already promises
"a request is followable end to end" and its own worked example names a `W3CTraceContext` that
does not exist as real code - the tracing half of this principle was written ahead of being
built.

Three real ways to close that gap, all verified directly rather than assumed from
documentation:

**Spring Boot 4.0's own `spring-boot-starter-opentelemetry`** (confirmed via Spring's own
release blog post, 2025-11-18) bundles `micrometer-tracing-bridge-otel` and
`micrometer-registry-otlp` behind one dependency - Spring's endorsed, code-integrated path.
The same post is explicit that this and the Java agent are **alternative** approaches, not
meant to run together.

**The OpenTelemetry Java agent** (`io.opentelemetry.javaagent:opentelemetry-javaagent`,
verified latest `2.31.1` directly against Maven Central) auto-instruments HTTP servers and
clients, JDBC, Kafka, RabbitMQ and gRPC by bytecode weaving at class-load time, attached with
`-javaagent:` and zero application code. Verified directly - not assumed - by downloading the
real jar and running a plain Java program under it:

- A method annotated `@WithSpan("usecase.place-order")` (from
  `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations`, same verified
  version `2.31.1`) produces a real exported span named exactly what was asked for, with a
  `@SpanAttribute("orderId")`-annotated parameter appearing as a span attribute
  (`orderId=order-123`) - confirmed in the logging exporter's own output.
- **The annotation is a no-op without the agent attached** - confirmed against the
  OpenTelemetry project's own documentation. A method compiled with `@WithSpan` and run in a
  plain `mvn test` JVM, with no `-javaagent:` flag, creates no span at all. This is a real,
  load-bearing operational dependency: tracing exists only when the process is launched with
  the agent, never as a property of the code alone.
- **The agent auto-populates the SLF4J MDC with `trace_id`, `span_id` and `trace_flags`
  whenever a span is active, with no code change** - confirmed in the same run: log lines
  written inside `placeOrder` carried a real `trace_id`/`span_id`; log lines written before and
  after carried empty ones. This is the direct link between this repository's existing
  structured-logging convention and a trace, and it costs nothing beyond adding the MDC keys
  to a log pattern or JSON encoder.

**Raw Micrometer Tracing plus `micrometer-tracing-bridge-otel`**, wired by hand, gives the same
capability as Spring's starter with more of the wiring exposed - no material advantage over
adopting the starter directly once Spring endorses one.

## Decision

**Traces are produced by the OpenTelemetry Java agent, attached at deployment time
(`-javaagent:opentelemetry-javaagent.jar`), never by `spring-boot-starter-opentelemetry` or a
hand-wired Micrometer Tracing bridge.** Custom, business-meaningful spans - one per use case,
named after its identifier - are added with `@WithSpan("usecase.<ID>")` from
`opentelemetry-instrumentation-annotations`, a dependency of `observability-support` so every
consumer compiles against the annotation for free, without forcing any of them to run under
the agent - the annotation is inert, not merely absent, until `-javaagent:` is on the command
line.

**Metrics stay on Micrometer and Spring Boot Actuator**, already in place in this repository
and already shaped around this principle's own guidance (tag by use case identifier, never by
anything unbounded). The agent's own metrics exporter is disabled
(`OTEL_METRICS_EXPORTER=none`); Micrometer's `micrometer-registry-otlp` is the path to the same
OTLP-speaking backend when one is needed, carrying this repository's own use-case-tagged
meters instead of the agent's generic, unlabelled ones. Health checks remain Actuator's
liveness/readiness groups, entirely unrelated to any of this.

**The correlation identifier and the trace id are deliberately two different things, kept for
different reasons.** `correlationId` is written by this platform's own filter on every request,
sampled or not, and travels in `EventEnvelope` regardless of what any tracing backend decided.
`trace_id` exists only for a request the sampler actually kept. A dropped trace still has a
correlation id in the logs and the event envelope; the reverse is never true. Neither
replaces the other.

**Sampling in production is head-based at the agent, with tail-based sampling at a Collector
as the answer to the head-based sampler's real weakness.** `OTEL_TRACES_SAMPLER=parentbased_traceidratio`
with `OTEL_TRACES_SAMPLER_ARG=<rate>` (both confirmed against OpenTelemetry's own
configuration reference) samples a fixed fraction of root spans and respects an existing
sampling decision from an upstream caller. Its known failure: a rare error or a slow request is
exactly as likely to be dropped as a fast, successful one, so the traces most worth having are
kept by chance, not by design. Sampling that keeps every error and every slow request
regardless of the head-based rate needs a policy made after the fact, which is what an
OpenTelemetry Collector's tail-sampling processor is for - a decision made once the whole
trace exists, deployed independently of any one service's release.

## Consequences

**Good** — Auto-instrumentation covers every remote-call kind this platform's own
`ResilienceRules.remoteCallsDeclareTimeouts()` already tracks (HTTP, JDBC, Kafka, RabbitMQ,
gRPC) with no per-adapter code, and covers third-party library internals a hand-wired
Micrometer bridge would not reach. The MDC injection this ADR confirms means every log line
already correlated by `correlationId` gains a `trace_id` for free the moment the agent is
attached - no second correlation mechanism to build.

**Bad** — Tracing is now an operational property of how a service is launched, not a property
its own test suite can verify: `mvn test` never runs under `-javaagent:`, so no test in this
repository proves a span was actually created. `@WithSpan` compiles and does nothing under
test, silently, which a reviewer who has not read this ADR could mistake for tracing having
been wired incorrectly rather than simply not being active outside a real deployment. The
agent's own version must be tracked against this repository's own library versions
independently of Maven's dependency resolution, since it instruments bytecode the build never
sees.

**Neutral** — Running metrics through Micrometer while running traces through the agent means
two different mechanisms feed one observability backend rather than one mechanism feeding it
directly, a seam Spring's own starter would not have. Given this repository's metrics story
already predates this decision and is already shaped around P-060's own tagging rule, that
seam costs nothing today; it would be worth re-examining only if Micrometer's own
Observation-based tracing ever became the same auto-instrumentation-by-default the agent
already is.

## Alternatives considered

### `spring-boot-starter-opentelemetry` (Spring Boot 4.0's own starter)

The better-integrated choice for a team building purely Spring-shaped services with no need to
trace third-party library internals the way the agent's bytecode weaving reaches. Rejected
here because the platform's own explicit choice is the agent, made for exactly the auto-
instrumentation breadth a code-level starter cannot match - and Spring's own guidance is not to
combine the two.

### Raw Micrometer Tracing + `micrometer-tracing-bridge-otel`, hand-wired

Functionally close to Spring's starter, with the wiring spelled out in this platform's own
code instead of a starter's autoconfiguration. Rejected for the same reason `temporal-support`
and `blob-storage-support` did not hand-roll wiring a maintained starter already does correctly
elsewhere in this repository: no advantage over adopting a real starter once one exists,
and here the platform's actual choice is the agent, not a Spring-integrated alternative to it.

### Head-based sampling alone, no Collector

Simpler - no extra infrastructure to run. Rejected as the sole answer to production sampling:
a fixed-rate sampler drops the rare failing or slow request as readily as an ordinary one, and
those are disproportionately the requests worth having. Head-based sampling stays as the
agent-level default; tail-based sampling at a Collector is the documented answer for a
deployment that needs errors and slow requests kept regardless of the overall rate, not a
requirement this ADR imposes on every environment.

## Revisit when

Spring's own `spring-boot-starter-opentelemetry` reaches a maturity or a level of
auto-instrumentation breadth that removes the platform's reason for choosing the agent - or
this repository's own multi-service auto-instrumentation needs shrink to the point that a
code-integrated starter covers them fully. Re-verify the agent's MDC-injection and `@WithSpan`
behaviour against this ADR's own recorded evidence whenever the pinned
`opentelemetry-javaagent`/`opentelemetry-instrumentation-annotations` version changes by a
major version.
