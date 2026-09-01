package com.acme.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * Establishes the correlation identifier for the duration of one gRPC call - the RPC-transport
 * twin of {@code observability-support}'s {@code CorrelationIdFilter}.
 *
 * <p>Takes the inbound {@code x-correlation-id} metadata entry when the caller supplied one that
 * is safe to echo and to log, so a trace started at an HTTP edge and forwarded by
 * {@code CorrelationClientInterceptor} continues here rather than restarting. Mints a UUID
 * otherwise. Echoes the identifier on the response headers, the gRPC equivalent of
 * {@code CorrelationIdFilter} echoing a response header.
 *
 * <p>{@code @GlobalServerInterceptor} is what applies this to every {@code @GrpcService} in the
 * process without each one wiring it by hand - Spring Boot's {@code DefaultGrpcServiceConfigurer}
 * discovers every bean carrying it.
 *
 * <p>MDC is set around each listener callback, not just at the point this interceptor runs,
 * because gRPC's actual service method invocation happens inside a later callback
 * ({@code onHalfClose} for a unary call) rather than during {@code interceptCall} itself - the
 * same reason {@code CorrelationIdFilter}'s placement matters for a servlet request, transposed
 * to gRPC's different callback shape. Every wrapped listener method nests every interceptor
 * registered after this one, {@code DomainExceptionGrpcAdvice} included, so the MDC value this
 * class establishes is visible there regardless of the relative order interceptor beans happen to
 * be discovered in.
 */
@GlobalServerInterceptor
public class CorrelationServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> CORRELATION_KEY =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Set here so every downstream listener callback, and {@code DomainExceptionGrpcAdvice}, can
     * read it. Referenced as a literal in both classes to keep the module dependency graph flat -
     * see {@code DomainExceptionGrpcAdvice} for why. */
    private static final String CORRELATION_MDC_KEY = "correlationId";

    /** Same acceptable-identifier shape as {@code observability-support}'s {@code Correlation} -
     * a caller-controlled value reaching both a response header and every log line for the call
     * is attacker-controlled data, and an unbounded or newline-carrying value is a way to corrupt
     * either sink. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:@=+-]{1,128}");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String correlationId = resolve(headers);
        ServerCall<ReqT, RespT> callEchoingCorrelation = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
                responseHeaders.put(CORRELATION_KEY, correlationId);
                super.sendHeaders(responseHeaders);
            }
        };

        ServerCall.Listener<ReqT> delegate;
        MDC.put(CORRELATION_MDC_KEY, correlationId);
        try {
            delegate = next.startCall(callEchoingCorrelation, headers);
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                withMdc(correlationId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withMdc(correlationId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withMdc(correlationId, super::onCancel);
            }

            @Override
            public void onComplete() {
                withMdc(correlationId, super::onComplete);
            }

            @Override
            public void onReady() {
                withMdc(correlationId, super::onReady);
            }
        };
    }

    private static void withMdc(String correlationId, Runnable action) {
        MDC.put(CORRELATION_MDC_KEY, correlationId);
        try {
            action.run();
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    private static String resolve(Metadata headers) {
        String candidate = headers.get(CORRELATION_KEY);
        if (candidate != null && ACCEPTABLE.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
