package com.acme.kernel.error;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Base type for failures that carry domain meaning.
 *
 * <p>Three things travel with every failure: a {@link ErrorKind kind} the edge maps to a
 * status, a stable {@link #code()} a client can branch on, and {@link #details()} that
 * name the offending values. The message is for humans reading logs and is never parsed.
 *
 * <p>Put nothing in {@code details} that must not appear in a client response or a log
 * line. It crosses both boundaries.
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorKind kind;
    private final String code;
    private final transient Map<String, Object> details;

    protected DomainException(ErrorKind kind, String code, String message, Map<String, Object> details) {
        this(kind, code, message, details, null);
    }

    protected DomainException(
            ErrorKind kind, String code, String message, Map<String, Object> details, @Nullable Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    /** What the caller should do about it. */
    public final ErrorKind kind() {
        return kind;
    }

    /**
     * Stable, machine-readable identifier such as {@code "order.already-cancelled"}.
     *
     * <p>Part of the API. Changing one breaks clients that branch on it, exactly as
     * renaming a field would.
     */
    public final String code() {
        return code;
    }

    /** Structured context, safe to serialise to the client. */
    public final Map<String, Object> details() {
        return details;
    }
}
