---
name: resilience
description: Make calls out of a service survive the dependency having a bad day - timeouts, bounded retries, concurrency limits and fallbacks using Spring Framework 7's core resilience support and declarative HTTP clients. Use when adding a call to another service or a slow dependency, when handling failures from one, or when a slow upstream is exhausting threads. Note Spring Cloud CircuitBreaker and spring-retry are NOT available here.
---

# Resilience

The default in most clients is to wait indefinitely. One slow dependency then consumes every
request thread, and a service that is merely degraded upstream becomes unavailable here. The
failure is never the timeout; it is having none.

Spring Cloud is not available on Boot 4.1 ([ADR-0004](../../../docs/adr/0004-do-not-adopt-spring-cloud.md)),
and `spring-retry` was dropped from the Boot BOM. Everything below is core Spring Framework 7.

## 1. Every remote call gets a timeout

Declarative HTTP clients make it configuration rather than something to remember:

```java
@HttpExchange("/invoices")
public interface BillingClient {
    @PostExchange
    InvoiceResponse create(@RequestBody CreateInvoiceRequest request);
}

@ArchConfig
@Configuration
@ImportHttpServices(group = "billing", types = BillingClient.class)
class BillingClientConfig {}
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

Set the read timeout from the dependency's stated p99 plus headroom - not from how long it usually
takes. A timeout longer than your own request budget protects nothing.

Databases and brokers need this too: connection-acquisition timeout, statement timeout, producer
delivery timeout.

## 2. Retry only what is safe to repeat

```java
@Retryable(maxAttempts = 3, delay = 200, multiplier = 2.0, includes = DependencyFailure.class)
public InvoiceId createInvoice(Order order) { … }
```

Enable it explicitly - do not assume Boot switches it on:

```java
@Configuration
@EnableResilientMethods
class ResilienceConfig {}
```

Rules that matter more than the parameters:

- Retry only idempotent operations. A retried POST that is not idempotent charges twice.
- **A timeout is not a failure to retry blindly.** You do not know whether the work happened.
  Either the operation is idempotent by key, or you must reconcile rather than retry.
- Always jitter and cap. Synchronised retries across instances are a self-inflicted denial of
  service on a dependency already struggling.
- Never retry a 4xx. The request is wrong and will stay wrong.

## 3. Bound concurrency

`@ConcurrencyLimit` caps how much of this service one dependency can consume:

```java
@ConcurrencyLimit(10)
public InvoiceResponse create(CreateInvoiceRequest request) { … }
```

This is the bulkhead. Without it, a dependency at ten-second latency will hold every thread you
have, and requests that never touch it start failing too.

## 4. Decide what happens when it stays down

Fail fast with `ErrorKind.DEPENDENCY_FAILURE` (502) or `TIMEOUT` (504), serve degraded data, or
queue the work for later. Choose deliberately, per call. The worst outcome is the unconsidered one:
an exception that becomes a 500 and tells the caller nothing about whether to retry.

## Declaring it

`ResilienceRules.remoteCallsDeclareTimeouts` requires outbound adapters of kind `HTTP_CLIENT`,
`MESSAGING` or `CACHE` to claim the principle, so the decision is findable:

```java
@OutboundAdapter(port = BillingPort.class, kind = AdapterKind.HTTP_CLIENT)
@ImplementsPrinciple(value = "P-051", note = "2s connect / 5s read; 3 retries; falls back to queued invoicing")
public class HttpBillingAdapter implements BillingPort { }
```

The build cannot read a timeout out of a YAML file, so what it checks is that someone thought about
it and left a pointer. Make the note say the actual numbers and the actual fallback.
