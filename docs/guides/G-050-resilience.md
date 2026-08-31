# G-050 — Calling other systems

Spring Cloud is not available here ([ADR-0004](../adr/0004-do-not-adopt-spring-cloud.md)) and
`spring-retry` was dropped from the Boot BOM. Everything below is core Spring Framework 7. The full
procedure is in the `resilience` skill.

## Every remote call gets a timeout

The default in most clients is to wait forever. One slow dependency then consumes every request
thread, and a service that is merely degraded upstream becomes unavailable here.

Declarative HTTP clients make it configuration rather than something to remember:

```java
@HttpExchange("/invoices")
public interface BillingClient { }
```

```yaml
spring:
  http:
    serviceclient:
      billing:
        base-url: ${BILLING_URL}
        connect-timeout: 2s
        read-timeout: 5s
```

Set the read timeout from the dependency's stated p99 plus headroom, not from how long it usually
takes. A timeout longer than your own request budget protects nothing.

## Retry only what is safe to repeat

`@Retryable` with `@EnableResilientMethods` on a configuration class — declare it explicitly, do not
assume Boot switches it on. Jitter and cap every backoff: synchronised retries across instances are
a self-inflicted denial of service on a dependency that is already struggling.

A timeout is **not** a failure to retry blindly. You do not know whether the work happened. Either
the operation is idempotent by key, or you reconcile rather than retry.

Never retry a 4xx.

## Bound concurrency

`@ConcurrencyLimit(n)` is the bulkhead. Without it, a dependency at ten-second latency holds every
thread you have, and requests that never touch it start failing too.

## Declare the decision

`ResilienceRules.remoteCallsDeclareTimeouts` requires outbound adapters of kind `HTTP_CLIENT`,
`MESSAGING` or `CACHE` to carry `@ImplementsPrinciple(value = "P-051", note = "…")`. The build
cannot read a timeout out of a YAML file; what it can check is that someone decided, and left a
pointer. Put the actual numbers and the actual fallback in the note.
