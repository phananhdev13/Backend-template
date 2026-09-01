# ADR-0015 — Spring Boot 4.1's native gRPC starters for internal service-to-service RPC

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-01 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Every inter-service call this platform has modelled so far is either a broadcast fact
([ADR-0007](0007-broker-neutral-event-contracts.md)) or a point-to-point job
([ADR-0014](0014-rabbitmq-classic-queues-for-point-to-point-tasks.md)). Neither fits a call
that is genuinely RPC-shaped: one service asking another to do something now and wanting an
answer, sometimes a stream of them, with the caller blocked (or asynchronously awaiting) on the
result the way it would be calling its own database. REST over HTTP can serve this and is what
[api-design](../../.claude/skills/api-design/SKILL.md) already covers for a public API, but it
carries JSON's serialisation cost and HTTP/1.1's per-connection request limits into a path that
is purely internal, where a binary, HTTP/2-multiplexed protocol with generated, statically-typed
stubs on both ends is a better fit and has no external client to keep compatible with.

Adopting gRPC has historically meant one of: hand-wiring `grpc-netty-shaded` and a `Server`
bean's lifecycle by hand, or the third-party `grpc-spring-boot-starter`
(`grpc-ecosystem/grpc-spring`), which targets Spring Boot 2.7 / Spring Cloud 2021.0.8 and has no
Boot 4 support. Spring's own `spring-grpc` project (`spring-projects/spring-grpc`) exists and
reached 1.1.1, but the significant fact - verified against Maven Central's own repository
metadata, not its search index, which lags by over a year - is that **Spring Boot 4.1.1 itself
now ships first-party gRPC autoconfiguration**: `org.springframework.boot:spring-boot-grpc-server`
and `spring-boot-grpc-client` (plus their `spring-boot-starter-grpc-server` /
`-client` starters), built on top of `spring-grpc-core` as a re-packaged engine, at exactly this
repository's Boot version (4.1.1, a GA release, not a milestone). Test-side support exists too,
as separately-versioned `spring-boot-starter-grpc-server-test` / `-client-test` artifacts (also
GA at 4.1.1) providing `@AutoConfigureTestGrpcTransport` - the in-process-transport equivalent of
`@AutoConfigureMockMvc` for RPC - and `@LocalGrpcServerPort` for a real-socket test.
`spring-boot-dependencies:4.1.1`'s own BOM manages `io.grpc:grpc-java` at `1.83.1` and
`protobuf-java` at `4.35.1`, both consistent with Netty `4.2.17.Final` also under that BOM;
Boot's grpc starters deliberately depend on unshaded `grpc-netty` rather than
`grpc-netty-shaded` specifically so gRPC shares that one managed Netty version rather than
carrying a private shaded copy.

## Decision

**Internal RPC between services goes over gRPC via Spring Boot 4.1's own
`spring-boot-starter-grpc-server` / `-client` starters, never a hand-wired `grpc-netty` server
or the unmaintained third-party starter, with `libs/grpc-support` supplying the two things
Boot's autoconfiguration leaves to the application: exception translation and correlation
propagation.**

- A service exposing RPCs adds `spring-boot-starter-grpc-server`; a service calling another's
  adds `spring-boot-starter-grpc-client`; a service doing both adds both. `libs/grpc-support`'s
  own dependencies on each are optional, so a service pulls in only the half it needs - the same
  shape `messaging-support` already uses for Kafka versus AMQP.
- `.proto` files compile via `io.github.ascopes:protobuf-maven-plugin`, not
  `org.xolstice.maven.plugins:protobuf-maven-plugin` - the latter is confirmed archived upstream
  (last release 0.6.1) and its own documentation points at the `ascopes` fork as the maintained
  successor. `protoc` and `protoc-gen-grpc-java` are pinned to `${protobuf-java.version}` and
  `${grpc-java.version}`, both already managed by `spring-boot-dependencies` - never repinned
  independently.
- `DomainExceptionGrpcAdvice` (a `@GrpcAdvice` bean) maps `DomainException` to a `Status` via
  `ErrorKindGrpcStatus`, the gRPC-transport twin of `web-support`'s RFC 9457 handler.
  `CorrelationServerInterceptor` and `CorrelationClientInterceptor` (`@GlobalServerInterceptor`
  / `@GlobalClientInterceptor`) carry the correlation identifier across the hop as gRPC metadata,
  the twin of `observability-support`'s HTTP filter.
- Every dependency here is proven against a real transport, not merely a compiling context:
  `libs/grpc-support`'s own test suite runs the full exception-and-correlation chain over
  Boot's in-process test transport, and separately over a real bound TCP socket and a real
  Netty server, both driving a real `@GrpcService` through generated stub code from a real
  compiled `.proto`.

## Consequences

**Good** — No hand-rolled server lifecycle, connection handling, or health/reflection service
wiring to maintain - Boot's own autoconfiguration already includes `grpc.health.v1.Health` and
`grpc.reflection.v1.ServerReflection` registration, TLS, and observation instrumentation. A
service adopting gRPC gets exception translation and correlation propagation with zero
configuration, the moment the relevant starter is on its classpath.

**Bad** — This repository now depends on Spring Boot's gRPC support at exactly the version it
shipped GA (4.1.1); the artifacts most load-bearing for testing
(`spring-boot-starter-grpc-server-test` / `-client-test`) are new enough that their long-term
maintenance posture past this release is unproven. Protobuf codegen adds a build-time dependency
on a native `protoc` binary the `ascopes` plugin downloads per platform, one more thing that can
fail in a locked-down build environment (the plugin supports a `path`-kind lookup and a
corporate-sanctioned-path override for exactly that case, should it become necessary).

**Neutral** — gRPC's status vocabulary is coarser than HTTP's (nine `ErrorKind`s map onto fewer
practically-distinct `Status.Code`s than HTTP status codes), which is a property of the
protocol, not a choice this ADR makes - `ErrorKindGrpcStatus` documents the specific mapping
chosen.

## Alternatives considered

### Third-party `grpc-spring-boot-starter` (`grpc-ecosystem/grpc-spring`)

The longest-established Spring integration for gRPC, with a mature `@GrpcService` /
`@GrpcClient` annotation model many engineers already know. Rejected because its documented
compatibility targets Spring Boot 2.7 and Spring Cloud 2021.0.8 - a major-version gap from this
repository's Boot 4.1, and adopting it would additionally reintroduce a dependency on Spring
Cloud's ecosystem this platform has already refused for other reasons
([ADR-0004](0004-do-not-adopt-spring-cloud.md)).

### Hand-wired `grpc-netty-shaded` with a `SmartLifecycle` server bean

Full control, no dependency on Boot's or spring-grpc's autoconfiguration maturing further. Lost
on pure duplication: Boot 4.1.1's own starters already provide everything this approach would
rebuild - server lifecycle, health and reflection services, TLS, `@GrpcAdvice`-based exception
dispatch, global interceptor discovery - at the exact Boot version this repository targets, with
none of the maintenance burden of owning that wiring.

### Depend on `org.springframework.grpc` directly rather than Boot's re-exported starters

`spring-grpc-core` is the actual engine either way, so functionally near-identical for the
server/client wiring itself. Rejected as the primary dependency because Boot's own
`spring-boot-starter-grpc-server` / `-client` are the artifacts this repository's other starters
already resemble in shape (`spring-boot-starter-data-redis`, `spring-boot-starter-amqp`), keep
version alignment automatic through `spring-boot-dependencies` rather than a second BOM to
track, and are what Boot's own release notes point application developers at.

## Revisit when

`spring-boot-starter-grpc-server-test` / `-client-test` graduate past their first GA release
with a materially different testing API, or a Boot 4.2+ release changes how global interceptors
or `@GrpcAdvice` are discovered - re-verify `libs/grpc-support`'s bean wiring against the new
autoconfiguration classes rather than assuming it still applies. Revisit the `ascopes`
protobuf-maven-plugin choice if `org.xolstice`'s plugin is ever revived with active maintenance,
or if the `ascopes` plugin itself lapses.
