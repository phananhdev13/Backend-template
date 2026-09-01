package com.acme.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;
import org.springframework.grpc.client.GlobalClientInterceptor;

/**
 * Carries the correlation identifier already established for this thread onto an outbound gRPC
 * call, so a call this service makes to another one continues the same trace rather than starting
 * a new one on the far side.
 *
 * <p>{@code @GlobalClientInterceptor} applies this to every channel Spring Boot's gRPC client
 * support builds, the client-side pair of {@code CorrelationServerInterceptor}'s
 * {@code @GlobalServerInterceptor}.
 *
 * <p>Silently does nothing when no identifier is established - a scheduled job or a startup hook
 * calling out over gRPC has none to propagate, and the far side's own
 * {@code CorrelationServerInterceptor} mints one in that case, the same way an unmarked HTTP
 * request does at an edge.
 */
@GlobalClientInterceptor
public class CorrelationClientInterceptor implements ClientInterceptor {

    /** Same key {@code CorrelationServerInterceptor} reads on the way in. */
    static final Metadata.Key<String> CORRELATION_KEY =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Referenced as a literal for the same reason the server-side interceptor does. */
    private static final String CORRELATION_MDC_KEY = "correlationId";

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String correlationId = MDC.get(CORRELATION_MDC_KEY);
                if (correlationId != null) {
                    headers.put(CORRELATION_KEY, correlationId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
