package com.acme.grpc;

import com.acme.kernel.error.DomainException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;

/**
 * Turns a failure into a gRPC status, once, for every {@code @GrpcService} in the process - the
 * RPC-transport twin of {@code web-support}'s {@code DomainExceptionHandler}.
 *
 * <p>Spring Boot's {@code GrpcServerAutoConfiguration} discovers any {@link GrpcAdvice} bean and
 * wraps every service call with the interceptor that dispatches to it; declaring this as a
 * {@code @Bean} in {@link GrpcSupportAutoConfiguration} is the only wiring this class needs.
 */
@GrpcAdvice
public class DomainExceptionGrpcAdvice {

    private static final Logger log = LoggerFactory.getLogger(DomainExceptionGrpcAdvice.class);

    /**
     * The machine-readable {@link DomainException#code()}, echoed as a trailer so a caller can
     * branch on it the same way an RFC 9457 problem response's {@code type} lets an HTTP caller.
     */
    static final Metadata.Key<String> CODE_KEY = Metadata.Key.of("x-error-code", Metadata.ASCII_STRING_MARSHALLER);

    /** Set by {@link CorrelationServerInterceptor}. Referenced as a literal to keep the module
     * dependency graph flat - the same reason {@code DomainExceptionHandler} hardcodes this
     * name rather than depending on {@code observability-support} for one constant. */
    private static final String CORRELATION_MDC_KEY = "correlationId";

    static final Metadata.Key<String> CORRELATION_KEY =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcExceptionHandler(DomainException.class)
    public StatusException onDomainException(DomainException exception) {
        Status status = ErrorKindGrpcStatus.of(exception.kind()).withDescription(exception.getMessage());
        Metadata trailers = new Metadata();
        trailers.put(CODE_KEY, exception.code());
        addCorrelation(trailers);
        // 5xx-equivalent statuses are our fault and need a log line; the rest are the caller's
        // and would be noise at volume - the same split DomainExceptionHandler makes on HTTP
        // status, applied to gRPC's status codes instead.
        if (isServerFault(status.getCode())) {
            log.error("Use case failed: {} [{}]", exception.getMessage(), exception.code(), exception);
        } else {
            log.debug("Rejected call: {} [{}]", exception.getMessage(), exception.code());
        }
        return status.asException(trailers);
    }

    /**
     * The catch-all deliberately says nothing beyond "internal error."
     *
     * <p>An exception class name or a message in a trailer is reconnaissance the same way it
     * would be in an HTTP response body - it names the framework, sometimes the schema. The
     * detail goes to the log against the correlation identifier instead.
     */
    @GrpcExceptionHandler(Exception.class)
    public StatusException onUnexpectedFailure(Exception exception) {
        Metadata trailers = new Metadata();
        addCorrelation(trailers);
        log.error("Unhandled exception serving gRPC call", exception);
        return Status.INTERNAL
                .withDescription("The request could not be completed")
                .asException(trailers);
    }

    private static boolean isServerFault(Status.Code code) {
        return switch (code) {
            case INTERNAL, UNAVAILABLE, UNKNOWN, DATA_LOSS -> true;
            default -> false;
        };
    }

    private static void addCorrelation(Metadata trailers) {
        String correlationId = MDC.get(CORRELATION_MDC_KEY);
        if (correlationId != null) {
            trailers.put(CORRELATION_KEY, correlationId);
        }
    }
}
