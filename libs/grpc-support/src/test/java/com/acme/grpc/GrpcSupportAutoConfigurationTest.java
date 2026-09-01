package com.acme.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.grpc.fixture.GreeterService;
import com.acme.grpc.test.proto.GreetManyRequest;
import com.acme.grpc.test.proto.GreetReply;
import com.acme.grpc.test.proto.GreetRequest;
import com.acme.grpc.test.proto.GreeterGrpc;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * Proves the exception advice and both correlation interceptors actually work against a real
 * {@code @GrpcService} over a real gRPC transport - not merely that they compile.
 * {@code @AutoConfigureTestGrpcTransport} swaps in an in-process transport, which exercises the
 * same interceptor and advice machinery a real Netty transport does, on a real client/server stub
 * pair, just without a real socket.
 */
@SpringBootTest(classes = GrpcSupportAutoConfigurationTest.TestConfig.class)
@AutoConfigureTestGrpcTransport
class GrpcSupportAutoConfigurationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
        @Bean
        GreeterService greeterService() {
            return new GreeterService();
        }
    }

    @Autowired
    private GrpcChannelFactory channels;

    private GreeterGrpc.GreeterBlockingStub stub() {
        return GreeterGrpc.newBlockingStub(channels.createChannel("default"));
    }

    @Test
    void unaryCallSucceeds() {
        GreetReply reply = stub().greet(GreetRequest.newBuilder().setName("Ada").build());

        assertThat(reply.getMessage()).isEqualTo("Hello, Ada");
    }

    @Test
    void serverStreamingCallSucceeds() {
        Iterator<GreetReply> replies = stub().greetEveryone(GreetManyRequest.newBuilder()
                .addAllNames(List.of("Ada", "Grace"))
                .build());

        assertThat(replies)
                .toIterable()
                .extracting(GreetReply::getMessage)
                .containsExactly("Hello, Ada", "Hello, Grace");
    }

    @Test
    void domainExceptionMapsToAGrpcStatusWithACodeTrailer() {
        assertThatThrownBy(
                        () -> stub().greet(GreetRequest.newBuilder().setName("").build()))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(StatusRuntimeException.class))
                .satisfies(exception -> {
                    assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(exception.getTrailers().get(DomainExceptionGrpcAdvice.CODE_KEY))
                            .isEqualTo("validation.failed");
                });
    }

    @Test
    void correlationIdSentByTheClientIsEchoedBackByTheServer() {
        Metadata requestHeaders = new Metadata();
        requestHeaders.put(CorrelationClientInterceptor.CORRELATION_KEY, "test-correlation-id");
        AtomicReference<Metadata> responseHeaders = new AtomicReference<>();

        stub().withInterceptors(
                        MetadataUtils.newAttachHeadersInterceptor(requestHeaders),
                        MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, new AtomicReference<>()))
                .greet(GreetRequest.newBuilder().setName("Ada").build());

        assertThat(responseHeaders.get().get(CorrelationServerInterceptor.CORRELATION_KEY))
                .isEqualTo("test-correlation-id");
    }

    @Test
    void correlationIdIsMintedWhenTheClientSendsNone() {
        AtomicReference<Metadata> responseHeaders = new AtomicReference<>();

        stub().withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, new AtomicReference<>()))
                .greet(GreetRequest.newBuilder().setName("Ada").build());

        assertThat(responseHeaders.get().get(CorrelationServerInterceptor.CORRELATION_KEY))
                .isNotBlank();
    }
}
