---
name: grpc
description: Call another service internally over gRPC in this repo - when to reach for it over REST or a broker, the .proto and codegen setup, exposing a @GrpcService, wrapping a client stub as an @OutboundAdapter with a deadline, and the exception/correlation wiring grpc-support supplies. Use whenever a change involves a .proto file, a *Grpc.*ImplBase, a GrpcChannelFactory, or unary/streaming internal RPC between services.
---

# gRPC

Spring Boot 4.1 ships gRPC support natively - `spring-boot-starter-grpc-server` and
`spring-boot-starter-grpc-client`, not a third-party add-on. `libs/grpc-support` wires the two
cross-cutting concerns every internal RPC call needs on top of that: one exception translation
point, and correlation identifier propagation across the hop. See
[P-043](../../../docs/principles/P-043-grpc-internal-rpc.md) for the reasoning.

## Reach for gRPC only for internal, RPC-shaped calls

| Shape of the call | Use |
|---|---|
| One service asks another to do something now and wants an answer (or a stream of them) | gRPC |
| A fact any number of interested parties should hear about | an [event](../events/SKILL.md) |
| Work for exactly one worker, fire-and-forget | a [task queue](../task-queues/SKILL.md) |
| A public API a browser or an external client calls | REST, per the [api-design](../api-design/SKILL.md) skill |

gRPC is internal, service-to-service, and synchronous-shaped even when the RPC itself streams.
Modelling a broadcast fact as a unary call because gRPC was already in front of you is the
mistake P-043 exists to catch - if a second service will ever need the same fact, it was an
event.

## Define the contract

A `.proto` file under `src/main/proto` (or, for a demonstration/test-only service with no real
consumer, `src/test/proto`) is compiled by `io.github.ascopes:protobuf-maven-plugin`, configured
in the service's `pom.xml`:

```xml
<plugin>
  <groupId>io.github.ascopes</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <version>${protobuf-maven-plugin.version}</version>
  <configuration>
    <protoc>${protobuf-java.version}</protoc>
    <plugins>
      <plugin kind="binary-maven">
        <groupId>io.grpc</groupId>
        <artifactId>protoc-gen-grpc-java</artifactId>
        <version>${grpc-java.version}</version>
      </plugin>
    </plugins>
  </configuration>
</plugin>
```

`${protobuf-java.version}` and `${grpc-java.version}` are already managed by
`spring-boot-dependencies` through this repo's parent - never repin either independently, or the
generated stubs and the gRPC runtime on the classpath can disagree on wire-format details.

```proto
syntax = "proto3";
package com.acme.agentfactory.deployment;
option java_package = "com.acme.agentfactory.deployment.grpc";
option java_multiple_files = true;

service AgentDeploymentService {
  rpc Provision(ProvisionRequest) returns (ProvisionReply);
  rpc StreamStatus(ProvisionRequest) returns (stream StatusUpdate);
}
```

All four RPC shapes work the same way here: unary, server-streaming, client-streaming and
bidirectional all generate a `*ImplBase` method to override, differing only in whether the
parameter or return type is a plain message or a `StreamObserver`.

## Expose one

```java
@InboundAdapter(AdapterKind.RPC)
public class AgentDeploymentGrpcService extends AgentDeploymentServiceGrpc.AgentDeploymentServiceImplBase {

    private final ProvisionUseCase provision;   // an @InputPort, called like any other adapter

    @Override
    public void provision(ProvisionRequest request, StreamObserver<ProvisionReply> responseObserver) {
        provision.provision(new ProvisionCommand(request.getAgentId(), request.getVersion()));
        responseObserver.onNext(ProvisionReply.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
```

`@GrpcService` (from `org.springframework.grpc.server.service`) is what Boot's autoconfiguration
discovers to register the bean with the server; `@InboundAdapter(AdapterKind.RPC)` is this
platform's own role annotation, required the same way it is on a `@RestController` - add both.
Never throw the generated exception types or catch-and-rethrow a `Status` from inside a use case
that method calls; let a `DomainException` propagate and `grpc-support`'s `@GrpcAdvice` translates
it.

## Call one

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
        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                .provision(ProvisionRequest.newBuilder().setAgentId(agentId).setVersion(version).build());
    }
}
```

`"agent-factory"` is a channel name resolved from `spring.grpc.client.channels.agent-factory.address`
in `application.yml` - never a hard-coded host and port. `ResilienceRules.remoteCallsDeclareTimeouts`
fails this class without the `@ImplementsPrinciple("P-051")` claim, the same rule any
`HTTP_CLIENT`, `MESSAGING` or `CACHE` outbound adapter answers to - a gRPC call with no deadline
blocks a caller thread exactly as an HTTP call with none does.

## What grpc-support gives you for free

Add `com.acme:grpc-support` (via the platform BOM) alongside whichever starter a service needs,
and every service it exposes or calls gets, with no wiring of its own:

- **Exception translation.** A `DomainException` becomes a `Status` via `ErrorKindGrpcStatus`
  (`VALIDATION` → `INVALID_ARGUMENT`, `CONFLICT` → `ABORTED`, `BUSINESS_RULE` →
  `FAILED_PRECONDITION`, and so on - gRPC's status vocabulary is coarser than HTTP's, so this
  is the one place that translation is decided), with the domain's stable `code()` attached as
  an `x-error-code` trailer a caller can branch on, the gRPC-transport equivalent of an RFC 9457
  problem response's `type`.
- **Correlation propagation.** The identifier crosses the RPC hop as `x-correlation-id`
  metadata, minted if the caller sent none, echoed back on the response - the same shape
  `observability-support`'s HTTP filter gives a servlet request.

Neither needs a line of configuration; both activate the moment the relevant starter
(`spring-boot-starter-grpc-server` and/or `-client`) is on the classpath.

## Testing

`spring-boot-starter-grpc-server-test` / `-client-test` (test scope) plus
`@AutoConfigureTestGrpcTransport` swap in an in-process transport for a `@SpringBootTest` - fast,
no socket, and it exercises the exact same interceptor and advice chain a real Netty transport
does. Reach for this first, the same way an in-memory `MockMvc` request is the default for a REST
controller test.

Omit `@AutoConfigureTestGrpcTransport` and set `spring.grpc.server.port=0` for a test against a
real bound socket - inject the actual port with `@LocalGrpcServerPort` and build a plain
`Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())`
channel rather than going through `GrpcChannelFactory`, which the test transport annotation
would otherwise redirect. Keep this to the rare case that specifically needs to prove the real
transport, not exception mapping or correlation logic - `grpc-support`'s own test suite is a
worked example of both kinds side by side.

For a complete, real example of both a service and its exception/correlation handling actually
working end to end - not merely compiling - read `libs/grpc-support/src/test/java/com/acme/grpc/`:
`GrpcSupportAutoConfigurationTest` (in-process, exception mapping, correlation) and
`GrpcSupportRealSocketTest` (a real Netty server and a real TCP client).

## Checklist

- [ ] the call is genuinely internal RPC - not a fact for broadcast, not a job for one worker
- [ ] the `.proto`'s package and Java options follow the service's own package convention
- [ ] the exposing class is both `@GrpcService` and `@InboundAdapter(AdapterKind.RPC)`
- [ ] the use case behind it throws `DomainException` subtypes, never a `Status` or the
      generated stub's exception type
- [ ] a client wrapper is `@OutboundAdapter(kind = AdapterKind.RPC)` with `@ImplementsPrinciple("P-051")`
      and an actual deadline set per call
- [ ] the channel address comes from `spring.grpc.client.channels.<name>.address`, never a
      hard-coded host and port
