package com.acme.grpc.fixture;

import com.acme.grpc.test.proto.GreetReply;
import com.acme.grpc.test.proto.GreetRequest;
import com.acme.grpc.test.proto.GreeterGrpc;
import com.acme.kernel.error.ValidationException;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * A throwaway {@code @GrpcService}, real enough that this module's own tests prove
 * {@code DomainExceptionGrpcAdvice} and the correlation interceptors work against a real
 * transport rather than merely compile. Not a published contract.
 */
@GrpcService
public class GreeterService extends GreeterGrpc.GreeterImplBase {

    @Override
    public void greet(GreetRequest request, StreamObserver<GreetReply> responseObserver) {
        if (request.getName().isBlank()) {
            throw ValidationException.field("name", "must not be blank");
        }
        responseObserver.onNext(GreetReply.newBuilder()
                .setMessage("Hello, " + request.getName())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void greetEveryone(
            com.acme.grpc.test.proto.GreetManyRequest request, StreamObserver<GreetReply> responseObserver) {
        for (String name : request.getNamesList()) {
            responseObserver.onNext(
                    GreetReply.newBuilder().setMessage("Hello, " + name).build());
        }
        responseObserver.onCompleted();
    }
}
