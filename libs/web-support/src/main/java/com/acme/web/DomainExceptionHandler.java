package com.acme.web;

import com.acme.kernel.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a failure into an RFC 9457 problem response, once, for every controller in the service.
 *
 * <p>The alternative - each controller catching and choosing a status - produces an API where the
 * same failure means different things at different endpoints, and where adding an endpoint means
 * re-deciding questions that were already settled.
 */
@RestControllerAdvice
public class DomainExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DomainExceptionHandler.class);

    /** Set by CorrelationIdFilter. Referenced as a literal to keep the module dependency graph flat. */
    private static final String CORRELATION_MDC_KEY = "correlationId";

    private static final String CORRELATION_PROPERTY = "correlationId";

    @ExceptionHandler(DomainException.class)
    ProblemDetail onDomainException(DomainException exception, HttpServletRequest request) {
        HttpStatus status = ErrorKindStatus.of(exception.kind());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(ProblemTypes.of(exception.code()));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        exception.details().forEach(problem::setProperty);
        addCorrelation(problem);
        // 5xx is our fault and needs a log line; 4xx is the caller's and would be noise at volume.
        if (status.is5xxServerError()) {
            log.error("Use case failed: {} [{}]", exception.getMessage(), exception.code(), exception);
        } else {
            log.debug("Rejected request: {} [{}]", exception.getMessage(), exception.code());
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field",
                        error.getField(),
                        "problem",
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request failed validation");
        problem.setType(ProblemTypes.of("validation.failed"));
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);
        addCorrelation(problem);
        return problem;
    }

    /**
     * The catch-all deliberately says nothing.
     *
     * <p>An exception class name, a message or a stack frame in a response body is reconnaissance:
     * it names the framework, the ORM, sometimes the schema. The detail goes to the log against the
     * correlation identifier, so support can join the two without the client ever seeing it.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpectedFailure(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
        problem.setType(ProblemTypes.of("internal.error"));
        problem.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        addCorrelation(problem);
        log.error("Unhandled exception serving {}", request.getRequestURI(), exception);
        return problem;
    }

    private static void addCorrelation(ProblemDetail problem) {
        String correlationId = MDC.get(CORRELATION_MDC_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_PROPERTY, correlationId);
        }
    }
}
