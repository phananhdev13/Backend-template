package com.acme.kernel.error;

import java.util.Map;

/**
 * A domain rule forbids what was asked.
 *
 * <p>Thrown from the domain, not from a use case: if a use case decides that an order
 * cannot be cancelled, the rule now lives outside the object that owns it and the next
 * caller will not get the same answer.
 */
public final class BusinessRuleViolation extends DomainException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleViolation(String code, String message, Map<String, Object> details) {
        super(ErrorKind.BUSINESS_RULE, code, message, details);
    }

    public BusinessRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }
}
