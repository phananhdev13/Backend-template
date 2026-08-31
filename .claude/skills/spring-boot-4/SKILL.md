---
name: spring-boot-4
description: Spring Boot 4.1 / Java 21 API facts for this repo - what changed from Boot 3 and will compile-fail or silently misbehave if you write it from memory. Read this BEFORE writing or editing any Spring, JPA, Jackson, JUnit, Testcontainers or Maven dependency code here. Triggers - adding a starter, writing a controller/listener/repository/test, a @SpringBootTest that will not wire, an unresolvable artifact, "why is MockMvc null", Jackson or JUnit import errors.
---

# Spring Boot 4.1 on Java 21

Most Spring Boot knowledge in circulation is Boot 3. This repository is Boot **4.1.1**, on Spring
Framework 7 and Jakarta EE 11. The differences below are the ones that bite: each either fails to
compile, fails to resolve, or - worse - starts and behaves differently.

Check here before reaching for a remembered idiom. If something you expect is missing, it was
probably removed rather than moved.

## Renamed and removed artifacts

| Do not use | Use | Why it matters |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Still published, but deprecated in its own POM description |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | **Removed.** 404 on Maven Central at 4.1.1 |
| `spring-boot-starter-web-services` | `spring-boot-starter-webservices` | Renamed |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` | All oauth2 starters moved under `security-` |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | Testcontainers **2.x** renamed every module |
| `spring-retry` | core `org.springframework.resilience` | Dependency management removed from the BOM |
| anything `org.springframework.cloud` | see [ADR-0004](../../../docs/adr/0004-do-not-adopt-spring-cloud.md) | Current train targets Boot **4.0**, not 4.1 |
| `org.springframework.kafka:spring-kafka` alone | `org.springframework.boot:spring-boot-starter-kafka` | The bare library has no `KafkaTemplate`, `ProducerFactory` or `@KafkaListener` processing - see below |

Boot 4 split the old monolithic autoconfigure module. The convention is now module
`spring-boot-<tech>`, package `org.springframework.boot.<tech>`, starter
`spring-boot-starter-<tech>`.

**This bit us once already in this repo.** `messaging-support`, `order-service` and `agent-factory`
all originally depended on the bare `spring-kafka` library. It compiled cleanly and every unit test
passed, because nothing in a unit test starts a Spring context. The application itself would have
failed at startup with `UnsatisfiedDependencyException: No qualifying bean of type 'KafkaTemplate'`
- Kafka's autoconfiguration lives in its own module, `org.springframework.boot:spring-boot-kafka`
(classes under `org.springframework.boot.kafka.autoconfigure`), and only the **starter** pulls it in.
The lesson generalises: a dependency on the raw client library compiles against Boot's split
technology modules exactly like it would against the pre-split ones, but supplies none of the beans
that autoconfiguration would have created. If a class needs a bean Boot "usually just provides",
check which starter actually ships the autoconfiguration - not just the client jar - and prove it
with a context-loading test (`ApplicationContextRunner`), not only a unit test of the class in
isolation. See `MessagingSupportAutoConfigurationTest` for the pattern.

## Libraries whose major version changed

- **Jackson 3.1.5**, groupId and package **`tools.jackson`**. `com.fasterxml.jackson` imports are
  Jackson 2 and are flagged by the repo's edit hook. `ObjectMapper` lives at
  `tools.jackson.databind.ObjectMapper`.
- **JUnit Jupiter 6.0.3**. Let the BOM drive it; do not pin JUnit.
- **Testcontainers 2.0.5**. Major version jump from 1.x, so copied examples will not resolve.
- Jakarta EE 11: Servlet 6.1, Persistence 3.2, Validation 3.1. Hibernate 7.4.

## Testing — the changes that waste the most time

`@SpringBootTest` stopped auto-providing test infrastructure. A test that used to work now gets a
null field and an unhelpful message.

- MockMvc is **not** provided → add `@AutoConfigureMockMvc`.
- `TestRestTemplate` / `WebClient` are **not** provided → add `@AutoConfigureTestRestTemplate` or
  `@AutoConfigureRestTestClient`.
- `@MockBean` / `@SpyBean` are **removed** → `@MockitoBean` / `@MockitoSpyBean`
  (`org.springframework.test.context.bean.override.mockito`).
- `@PropertyMapping` moved to `org.springframework.boot.test.context`.
- The test auto-configurations were **split out of `spring-boot-test-autoconfigure`** into per-technology
  modules, and they moved package with it. `@AutoConfigureMockMvc` is now
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, and it arrives with
  `spring-boot-starter-webmvc-test` — **not** with `spring-boot-starter-test`. The old
  `org.springframework.boot.test.autoconfigure.web.servlet` package does not exist.
- AOT processing **ignores `-DskipTests`**. Use `-Dmaven.test.skip` when you mean it.

Assertions in this repo are AssertJ. `org.junit.jupiter.api.Assertions` is a banned import and
checkstyle will fail the build on it.

## New capabilities worth using instead of writing your own

**Declarative HTTP clients** replace OpenFeign. Annotate an interface with `@HttpExchange`,
register it with `@ImportHttpServices(group = "billing", types = BillingClient.class)`, and
configure it:

```yaml
spring:
  http:
    serviceclient:
      billing:
        base-url: https://billing.internal
        connect-timeout: 2s
        read-timeout: 5s
```

**Resilience is core Spring now** — `org.springframework.resilience.@Retryable` and
`@ConcurrencyLimit`, switched on with `@EnableResilientMethods` on a configuration class. Declare
it explicitly; do not assume Boot enables it. See the `resilience` skill.

**API versioning is native to Spring MVC** — `spring.mvc.apiversion.default`,
`spring.mvc.apiversion.use.header`, with path, header, query and media-type strategies. See the
`api-design` skill.

**Actuator** enables liveness and readiness probes by default; the health endpoint exposes
`liveness` and `readiness` groups without configuration.

## Other behaviour changes

- Undertow is gone (no Servlet 6.1 support). Tomcat or Jetty.
- Spring Data JPA `bootstrap-mode: deferred` now throws without an `AsyncTaskExecutor` bean.
- Apache Derby is deprecated; use H2 or a Testcontainer.
- Nullability is expressed with **JSpecify** (`org.jspecify.annotations.Nullable`), not
  `org.springframework.lang.Nullable`. Packages here are `@NullMarked` by default, so annotate
  what can be null rather than what cannot.

## Auto-configuration in a library module

Registration file — note the path, it is not `spring.factories` and not the Boot 2 location:

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

One fully-qualified class name per line.

## Verifying rather than trusting

Artifact coordinates change between minors. When unsure whether something exists at this version,
check rather than guess:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-webmvc/4.1.1/spring-boot-starter-webmvc-4.1.1.pom
```

`200` means it exists; `404` means it was renamed or removed - search the Boot 4 migration guide
for the replacement rather than downgrading.
