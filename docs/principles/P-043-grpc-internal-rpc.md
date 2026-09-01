# P-043 — gRPC is for internal service-to-service RPC, translated at one boundary

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `DomainRules.domainDependsOnlyOnDomain()`, `ErrorRules.domainNeverThrowsWebExceptions()`, `ResilienceRules.remoteCallsDeclareTimeouts()` in `libs/arch-test` |
| **Annotations** | `@InboundAdapter(AdapterKind.RPC)`, `@OutboundAdapter(kind = AdapterKind.RPC)`, `@ImplementsPrinciple` |
| **Guide** | [skill: grpc](../../.claude/skills/grpc/SKILL.md) |

## Rule

A call from one service to another that needs request/response or streaming RPC semantics -
not a fact to broadcast, not a job for one worker - goes over gRPC through
`libs/grpc-support`. A `DomainException` never reaches a caller as itself: exactly one
`@GrpcAdvice` bean maps it to a `io.grpc.Status`, the RPC-transport twin of `web-support`'s
single HTTP exception translation point. A `@GrpcService` is an `@InboundAdapter(AdapterKind.RPC)`;
a generated client stub wrapped for another service's use is an `@OutboundAdapter(kind =
AdapterKind.RPC)` and, like any other remote call, claims [P-051](P-051-remote-call-resilience.md).

## Why

**Generated protobuf and gRPC types are framework types the same way a JPA entity or a Kafka
DTO is, and the domain must not import them any more than it imports those.** A
`*Grpc.*ImplBase` class, a `StreamObserver`, a generated message's `com.google.protobuf.*`
superclass all carry framework machinery baked in by codegen - there is no way to use one
without depending on `io.grpc` or `com.google.protobuf`. `DomainRules.domainDependsOnlyOnDomain`
already forbids the domain depending on Spring, Hibernate, Kafka and RabbitMQ for exactly this
reason; gRPC and protobuf earn the same place in that list, not a separate rule, because the
failure mode is identical - a domain type that cannot be tested without a serialiser and a
transport library in the loop.

**A `Status` chosen inside a use case is the same mistake as a `ResponseStatusException`
thrown from one, wearing gRPC's clothes instead of Spring MVC's.** The use case that decides
"this order was already cancelled" does not know, and must not need to know, whether the
caller reached it over HTTP, gRPC or a message queue - that is exactly what
[P-050](P-050-error-handling.md) already says, and `ErrorRules.domainNeverThrowsWebExceptions`
now checks `io.grpc..` alongside `org.springframework.web..` for the same reason: the
transport that happens to be in front changes nothing about what the failure means.

**gRPC's status vocabulary is coarser than HTTP's, and that is a translation to get right
once, not per call site.** `CONFLICT` and `BUSINESS_RULE` both plausibly map to
`FAILED_PRECONDITION`, but a stale version is a concurrency race the caller can retry against
fresh state (`ABORTED`) while a business rule refusal is not (`FAILED_PRECONDITION` proper) -
a distinction easy to blur if every service's `@GrpcAdvice` reinvents the mapping instead of
sharing `grpc-support`'s `ErrorKindGrpcStatus`.

**An internal RPC hop that drops the correlation identifier breaks the one thing
[P-060](P-060-observability.md) promises: that a request stays followable end to end.** A
trace that reaches an HTTP edge, crosses into a gRPC call, and restarts on the other side with
a fresh identifier is exactly the gap P-060 exists to close - `grpc-support`'s
`CorrelationServerInterceptor` and `CorrelationClientInterceptor` are the RPC-transport pair of
`observability-support`'s HTTP filter, propagating the same identifier as gRPC metadata instead
of an HTTP header.

**A call that can hang still needs an answer for what happens when it does, whether it is
RPC, HTTP or a broker.** `ResilienceRules.remoteCallsDeclareTimeouts()` treats
`AdapterKind.RPC` as a remote kind alongside `HTTP_CLIENT`, `MESSAGING` and `CACHE` for that
reason: an `@OutboundAdapter` wrapping a generated client stub must claim P-051 like any other
remote call, and a gRPC deadline set on every call is that claim's concrete form the same way a
connect-and-read timeout is for an HTTP client.

## In code

```java
@InboundAdapter(AdapterKind.RPC)
public class AgentDeploymentGrpcService extends AgentDeploymentServiceGrpc.AgentDeploymentServiceImplBase {

    @Override
    public void provision(ProvisionRequest request, StreamObserver<ProvisionReply> responseObserver) {
        // translates the request, calls an @InputPort, translates the result - never decides
        provisionUseCase.provision(new ProvisionCommand(request.getAgentId(), request.getVersion()));
        responseObserver.onNext(ProvisionReply.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
```

A client calling another service's RPC declares the same P-051 obligation any other remote
call does:

```java
@OutboundAdapter(port = AgentDeploymentPort.class, kind = AdapterKind.RPC)
@ImplementsPrinciple(value = "P-051", note = "Deadline set per call via withDeadlineAfter")
public class GrpcAgentDeploymentAdapter implements AgentDeploymentPort {

    private final AgentDeploymentServiceGrpc.AgentDeploymentServiceBlockingStub stub;

    GrpcAgentDeploymentAdapter(GrpcChannelFactory channels) {
        this.stub = AgentDeploymentServiceGrpc.newBlockingStub(channels.createChannel("agent-factory"));
    }

    @Override
    public void provision(String agentId, int version) {
        stub.withDeadlineAfter(2, TimeUnit.SECONDS).provision(ProvisionRequest.newBuilder()
                .setAgentId(agentId).setVersion(version).build());
    }
}
```

Wrong - a use case catching the generated stub's exception and choosing a `Status` itself,
the gRPC-shaped version of a use case throwing `ResponseStatusException`:

```java
@UseCase(id = "UC-…")
public void activateVersion(ActivateAgentVersionCommand command) {
    try {
        deploymentStub.provision(...);
    } catch (StatusRuntimeException e) {
        throw new StatusRuntimeException(Status.INTERNAL); // the use case now knows it is gRPC
    }
}
```

## Enforcement

`DomainRules.domainDependsOnlyOnDomain()` fails a domain or application class depending on
`io.grpc..` or `com.google.protobuf..`, exactly as it already fails one depending on Hibernate
or Kafka. `ErrorRules.domainNeverThrowsWebExceptions()` fails the same classes depending on
`io.grpc..` specifically for exception types.
`ResilienceRules.remoteCallsDeclareTimeouts()` fails an `@OutboundAdapter(kind =
AdapterKind.RPC)` with no `@ImplementsPrinciple("P-051")`.

## Deviating

A service that only ever calls another over HTTP, or only ever reacts to events, has no
reason to add gRPC at all - `grpc-support`'s dependencies are optional for exactly this case,
the same way `caching-support`'s Redis dependency is. Adopting gRPC for a call that is
genuinely one-shot and tolerant of REST's overhead needs no `@Adr`; the reverse - modelling a
broadcast fact as a unary RPC because it was already in front of you - is what this principle
exists to catch, and is a design mistake to fix, not a deviation to record.

Related: [P-070](P-070-event-semantics.md) for when the call should be a published event
instead; [P-131](P-131-task-queues.md) for when it should be a queued job.
