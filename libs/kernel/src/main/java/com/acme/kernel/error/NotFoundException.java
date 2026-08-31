package com.acme.kernel.error;

import java.util.Map;

/** The addressed thing does not exist. */
public final class NotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String code, String message, Map<String, Object> details) {
        super(ErrorKind.NOT_FOUND, code, message, details);
    }

    /** Convenience for the common "this type, this id" case. */
    public static NotFoundException of(String type, Object id) {
        return new NotFoundException(
                type.toLowerCase(java.util.Locale.ROOT) + ".not-found",
                type + " " + id + " was not found",
                Map.of("type", type, "id", String.valueOf(id)));
    }
}
