package com.acme.web;

import com.acme.kernel.error.ErrorKind;
import org.springframework.http.HttpStatus;

/**
 * The single mapping from a domain failure category to an HTTP status.
 *
 * <p>Kept here, and nowhere else, so that a use case never has to know it is being called over
 * HTTP. Every entry is chosen for what it tells the caller to do next, which is the only thing a
 * status code is good for.
 */
public final class ErrorKindStatus {

    private ErrorKindStatus() {}

    /** The status that corresponds to a failure category. */
    public static HttpStatus of(ErrorKind kind) {
        return switch (kind) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            // 422, not 400: the request was well-formed and understood, and a rule refused it.
            // A client that retries a 400 unchanged is wrong; one that retries this may not be.
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case DEPENDENCY_FAILURE -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }
}
