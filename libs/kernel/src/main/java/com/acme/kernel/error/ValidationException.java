package com.acme.kernel.error;

import java.util.Map;

/** The request is malformed. Retrying it unchanged will fail the same way. */
public final class ValidationException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String code, String message, Map<String, Object> details) {
        super(ErrorKind.VALIDATION, code, message, details);
    }

    /** Convenience for a single offending field. */
    public static ValidationException field(String field, String problem) {
        return new ValidationException(
                "validation.failed", field + " " + problem, Map.of("field", field, "problem", problem));
    }
}
