# ADR-0004 — Do not adopt Spring Cloud

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Spring Cloud is the reflex answer to distributed-system plumbing in the Spring ecosystem,
and for a decade it was the right one. On Spring Boot 4.1 it is not available at all.

Spring Cloud releases as a **train**: a BOM pinning one Boot version and a set of mutually
tested components. The current train is **2025.1.3 "Oakwood"**, released 2026-08-20 — the
same day as Boot 4.1.1 — and it declares `spring-boot.version` = **4.0.8**. There is no
2026.0.x train. No published train supports any 4.1.x release.

This is not a soft constraint a version override works around. The train's components compile
against the Boot 4.0 autoconfiguration API; mixing them with 4.1.1 produces either a
`NoSuchMethodError` at context refresh or, worse, an autoconfiguration that silently does not
apply. Spring Cloud Stream 5.0.3 ships in that same train, so binders go the same way.

That leaves three options: pin Boot to 4.0.8, wait for a train supporting 4.1, or replace
each capability individually. The first contradicts ADR-0001 and buys a support window ending
2026-12-31; the second means shipping nothing, on a schedule nobody here controls. The third
turned out smaller than expected, because Spring Framework 7 absorbed two of the capabilities
teams most often reach to Spring Cloud for.

## Decision

**This template does not depend on Spring Cloud, on any Spring Cloud train, or on Spring
Cloud Stream.** No `spring-cloud-dependencies` import appears in the root `pom.xml`, and none
should be added on the argument that "a version exists".

Each capability is replaced explicitly:

| Reached for | Spring Cloud component | What we use instead |
|---|---|---|
| Declarative HTTP client | OpenFeign | Spring Framework 7 `@ImportHttpServices` + `spring.http.serviceclient.<group>.*` |
| Retry | spring-retry | `org.springframework.resilience.@Retryable` with `@EnableResilientMethods` |
| Bulkhead / circuit breaker | Spring Cloud CircuitBreaker | `org.springframework.resilience.@ConcurrencyLimit`, plus timeouts at the client |
| Event streaming | Spring Cloud Stream binders | plain `spring-kafka` 4.1.1 / `spring-amqp` 4.1.1 behind `@EventContract` (ADR-0007) |
| Service discovery | Eureka, Consul discovery | Kubernetes `Service` DNS — a stable name resolved by the platform |
| Externalised configuration | Spring Cloud Config Server | **no in-Boot replacement.** Kubernetes `ConfigMap`/`Secret` projected as environment variables or mounted files, consumed through ordinary `spring.config.import` and `@ConfigurationProperties` |
| Edge routing | Spring Cloud Gateway | **no in-Boot replacement.** An ingress controller, or a dedicated gateway product deployed as infrastructure — not as a Boot application in this reactor |

Two rows say "no replacement", and that is the honest answer rather than a gap we are
papering over. Config Server's dynamic refresh, encrypted properties and Git-backed history
have no equivalent inside Boot; a `ConfigMap` is a static projection, and changing it restarts
the pod. Gateway's filter chain, rate limiting and request-level routing likewise have none;
an ingress covers path and host routing and nothing more. Teams needing more must adopt a
product at the infrastructure layer and say so in their own ADR. Note also that
**spring-retry's dependency management was removed from the Boot BOM in 4.x**, so adding it
back means maintaining a hand-written pin.

## Consequences

**Good** — The dependency graph loses an entire coordinated release train and its upgrade
coupling: Boot upgrades no longer wait for a train. Kafka and AMQP are used directly, so
broker semantics are visible in code rather than mediated by a binder abstraction that has to
be reverse-engineered when it misbehaves.

**Bad** — Config Server and Gateway are genuinely lost, and teams arriving from a Spring Cloud
codebase will feel both; configuration refresh without a restart is gone. Tracing propagation,
once sleuth's job, is now Micrometer Tracing by hand, and client-side load balancing gives way
to the platform's mesh.

**Neutral** — `@ImportHttpServices` is not a drop-in for OpenFeign: interfaces use
`@HttpExchange`, not `@FeignClient`, and configuration lives under
`spring.http.serviceclient.<group>.*`. Mechanical to migrate; examples online are Feign-shaped.

## Alternatives considered

### Pin Spring Boot to 4.0.8 and adopt the 2025.1.3 train

The only configuration in which Spring Cloud works today, and defensible for a team that
genuinely depends on Config Server and Gateway. It lost because Boot 4.0's OSS support window
ends 2026-12-31 — four months from this decision. A template whose baseline goes end-of-life
before its first service reaches production has failed at its one job, and it would move only
when a train moved.

### Adopt Spring Cloud components individually, overriding the Boot version

Sometimes viable when a component is nearly Boot-version-agnostic. Not here: these hook Boot's
autoconfiguration, which changed between 4.0 and 4.1. The failure mode is not a compile error
but an autoconfiguration that quietly does not apply — a circuit breaker that never breaks,
discovered during an incident.

### Wait for a train that supports Boot 4.1

Reasonable if a date existed. None is published, and a train historically follows its Boot line
rather than catching up, so 4.1 support most likely arrives with the train targeting 4.2.

### Resilience4j directly, instead of `org.springframework.resilience`

More capable: bulkheads, rate limiters, time limiters, a richer circuit breaker state machine
and its own metrics. A real option, and the answer if requirements outgrow the Framework
annotations. It lost as the default because it is another dependency to version and another
vocabulary to teach, when `@Retryable` and `@ConcurrencyLimit` cover the common cases with
nothing added to the classpath.

## Revisit when

A Spring Cloud train publishes a BOM whose `spring-boot.version` is 4.1.x or later — check
that property directly, not the train's compatibility table. Revisit **independently and
sooner** if a service needs configuration refresh without a restart, or routing beyond what an
ingress expresses; either justifies a targeted ADR adopting one component, not reopening this.
