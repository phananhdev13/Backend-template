# P-051 — Every remote call has a timeout, a retry policy, and a bulkhead

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `ResilienceRules.remoteCallsDeclareTimeouts()` in `libs/arch-test` — it checks that the adapter *claims* P-051, not that a timeout is set; the numbers themselves are _review only_. `ResilienceRules.retriesOnlyOnIdempotentOperations()` (not implemented) |
| **Annotations** | `@OutboundAdapter`, `@ImplementsPrinciple`, `@Adr` |
| **Guide** | [G-050](../guides/G-050-resilience.md) |

## Rule

Every `@OutboundAdapter` whose `AdapterKind.isRemote()` declares a connect and read timeout,
an explicit retry policy, and a concurrency limit. Defaults are not a policy — state the
numbers where the adapter is.

Which kinds those are is [`AdapterKind`](../../libs/kernel/src/main/java/com/acme/kernel/arch/AdapterKind.java)'s
own answer rather than a list repeated here, so that adding a technology to the enum forces the
question rather than quietly escaping this principle. Today: `HTTP_CLIENT`, `MESSAGING`, `CACHE`,
`BLOB_STORAGE`, `RPC` and `WORKFLOW`. `PERSISTENCE` is excluded on purpose — a database call is
remote, but its budget is set once by the connection pool and the statement timeout rather than
per adapter.

`CACHE` deserves a note. A distributed cache reached through `@Cacheable` on a use case has no
adapter for this rule to inspect, and that is allowed: `caching-support` supplies the timeout and
the failure behaviour for that shape, so the budget is stated once in the module rather than at
every call site. See [P-130](P-130-caching-contracts.md) for the two shapes and what each owes.
This rule still governs a cache reached through an `@OutboundAdapter(kind = CACHE)`.

## Why

**Missing timeouts are how one slow dependency stops a whole service.** The default read
timeout on a plain `RestClient` is the OS socket default — effectively infinite. A payment
provider that stops responding without closing connections holds every request thread that
touches it. Tomcat's pool exhausts in under a minute at modest traffic, and then endpoints
that never call the provider start timing out too, because there is no thread left to serve
them. The alert fires on the wrong service, the dashboard shows the database healthy, and
the actual cause is three hops away. A 2-second read timeout converts that outage into a
degraded feature.

**Retries without a budget amplify the incident that caused them.** A dependency at 50%
error rate with three uniform retries receives roughly double its normal request volume at
exactly the moment it is failing — a retry storm that keeps it down after the original cause
has passed. Exponential backoff with jitter is the difference between helping it recover and
preventing it. Uniform backoff is nearly as bad as none: every client retries on the same
tick, and the recovering dependency is hit by a synchronised wave.

**Retrying a non-idempotent operation is a correctness bug, not a performance one.** A
`POST /payments` that times out after the provider committed, retried twice, is a customer
charged three times. `ErrorKind.TIMEOUT` exists precisely because the outcome is *unknown*
([P-050](P-050-error-handling.md)). Retry only what is safe to repeat: `GET` and `DELETE`
always, `POST` only with an idempotency key the provider honours
([P-071](P-071-idempotency.md)).

**A bulkhead bounds the damage.** A concurrency limit on the adapter caps how much of the
service one dependency can consume. Without it, a dependency that slows from 50 ms to 5 s
does not fail — it absorbs the entire thread pool while reporting success, which is harder
to detect than an outright error and takes everything else down with it.

**Spring Cloud is deliberately not on the classpath here.** Its 2025.1.x train targets Boot
4.0.x, not 4.1, so this template uses Spring Framework 7's built-ins:
`@ImportHttpServices` for declarative clients and `org.springframework.resilience`'s
`@Retryable` and `@ConcurrencyLimit`, enabled with `@EnableResilientMethods`. Do not
reintroduce a resilience library without an ADR — you would be pinning the whole platform to
an older Boot line.

## In code

Declarative client, with the group configurer carrying the timeouts:

```java
package com.acme.orders.ordering.config;

@ArchConfig
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
@ImportHttpServices(group = "payments", types = PaymentProviderClient.class)
class PaymentClientConfig {

    @Bean
    RestClientHttpServiceGroupConfigurer paymentTimeouts(PaymentProperties properties) {
        return groups -> groups.filterByName("payments").forEachClient((group, builder) ->
                builder.baseUrl(properties.baseUrl())
                       .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                               .build(ClientHttpRequestFactorySettings.defaults()
                                       .withConnectTimeout(Duration.ofMillis(500))
                                       .withReadTimeout(Duration.ofSeconds(2)))));
    }
}
```

The adapter states its retry and bulkhead policy, and translates failures inwards:

```java
package com.acme.orders.ordering.adapter.out.payment;

@OutboundAdapter(port = PaymentGateway.class, kind = AdapterKind.HTTP_CLIENT)
@ImplementsPrinciple(value = {"P-051"}, note = "2s read timeout, 3 retries with jitter, 20-way bulkhead")
@Component
class HttpPaymentGateway implements PaymentGateway {

    private final PaymentProviderClient client;      // @ImportHttpServices interface

    @Override
    @ConcurrencyLimit(20)                            // bulkhead: at most 20 in flight
    @Retryable(maxAttempts = 3,
               delay = 200, multiplier = 2.0, jitter = 100, maxDelay = 2_000,
               includes = { ResourceAccessException.class, HttpServerErrorException.class },
               excludes = { HttpClientErrorException.class })     // 4xx will not improve
    public PaymentReceipt capture(OrderId orderId, Money amount, IdempotencyKey key) {
        try {
            var response = client.capture(new CaptureRequest(orderId.value(), amount.amount(), key.value()));
            return new PaymentReceipt(new PaymentReference(response.reference()));
        } catch (ResourceAccessException timeout) {
            // The provider may have captured. Say so: the caller must not blindly retry.
            throw new PaymentUnavailable(ErrorKind.TIMEOUT, "payment.timeout",
                    "Payment provider did not respond in time",
                    Map.of("orderId", orderId.value().toString()), timeout);
        }
    }
}

/** Service-local DomainException subtype; the kernel supplies the base and the ErrorKind. */
final class PaymentUnavailable extends DomainException {
    PaymentUnavailable(ErrorKind kind, String code, String message, Map<String, Object> details, Throwable cause) {
        super(kind, code, message, details, cause);
    }
}
```

Wrong — unbounded wait, blind retry, no bulkhead:

```java
@Override
public PaymentReceipt capture(OrderId orderId, Money amount, IdempotencyKey key) {
    // No timeout: inherits the socket default. No key on the retry: double charge.
    return restClient.post().uri("/captures").body(…).retrieve().body(PaymentReceipt.class);
}
```

## Enforcement

`ResilienceRules.remoteCallsDeclareTimeouts()` fails an `@OutboundAdapter` of a remote kind
whose client bean has no configured connect and read timeout, resolved through the
`RestClientHttpServiceGroupConfigurer` or the `ClientHttpRequestFactorySettings` it is built
from:

```
com.acme.orders.ordering.adapter.out.payment.HttpPaymentGateway (kind = HTTP_CLIENT)
uses HTTP service group 'payments', which sets no read timeout. An unbounded read
timeout exhausts the request thread pool under a slow dependency.
See docs/principles/P-051-remote-call-resilience.md
```

`ResilienceRules.remoteCallsDeclareTimeouts()` fails a remote adapter with no
`@ConcurrencyLimit` on its public methods and no `@Adr` exempting it.

`ResilienceRules.retriesOnlyOnIdempotentOperations()` (not implemented) fails a `@Retryable` method that issues
a `POST`/`PATCH` without an idempotency key parameter, and fails a `@Retryable` whose
`includes` admits `HttpClientErrorException` — retrying a 4xx is always wrong.

## Deviating

A genuinely long operation — a report export, a bulk upload — needs a long timeout, not no
timeout. Set it explicitly, give it its own bulkhead so it cannot compete with interactive
traffic, and record the numbers in an `@Adr`.

Fire-and-forget calls where loss is acceptable (telemetry) may skip retries entirely; say so
in `@ImplementsPrinciple.note` rather than leaving the absence to be read as an oversight.

Circuit breaking is not covered by the core annotations. Where a dependency needs one, the
current answer is a `@ConcurrencyLimit` plus fail-fast on `TIMEOUT`; anything more requires
an ADR, because it means adding a library.
