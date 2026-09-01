package com.acme.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.grpc.fixture.GreeterService;
import com.acme.grpc.test.proto.GreetReply;
import com.acme.grpc.test.proto.GreetRequest;
import com.acme.grpc.test.proto.GreeterGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the whole chain against a real bound TCP socket and a real Netty server, not the
 * in-process transport {@link GrpcSupportAutoConfigurationTest} uses - the two are complementary:
 * that test proves the advice and interceptors are wired correctly; this one proves the real
 * transport this module actually ships to production carries a call end to end at all. No
 * {@code @AutoConfigureTestGrpcTransport} here, deliberately: that annotation is what swaps in the
 * in-process transport, and this test exists specifically to bypass it.
 */
@SpringBootTest(classes = GrpcSupportRealSocketTest.TestConfig.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class GrpcSupportRealSocketTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
        @Bean
        GreeterService greeterService() {
            return new GreeterService();
        }
    }

    @LocalGrpcServerPort
    private int port;

    private ManagedChannel channel;

    @AfterEach
    void shutdown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void aRealClientOverARealSocketReachesTheRealServer() {
        channel = Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);

        GreetReply reply = stub.greet(GreetRequest.newBuilder().setName("Ada").build());

        assertThat(reply.getMessage()).isEqualTo("Hello, Ada");
    }
}
