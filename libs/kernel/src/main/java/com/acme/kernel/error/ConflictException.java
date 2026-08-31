package com.acme.kernel.error;

import java.util.Map;

/**
 * The request is valid, but current state will not accept it.
 *
 * <p>Typically a concurrent modification or a uniqueness violation. Distinct from
 * {@link BusinessRuleViolation} in that the same request may well succeed later.
 */
public final class ConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String code, String message, Map<String, Object> details) {
        super(ErrorKind.CONFLICT, code, message, details);
    }
}
