package com.acme.kernel.error;

/**
 * The category of a failure, chosen for what the caller should do about it.
 *
 * <p>These are the distinctions that change a caller's behaviour: retry, fix the
 * request, give up, or escalate. Anything finer belongs in the error code.
 */
public enum ErrorKind {

    /** The request is malformed or self-contradictory. Retrying it unchanged will fail again. */
    VALIDATION,

    /** The addressed thing does not exist, or the caller may not know that it does. */
    NOT_FOUND,

    /** The request is valid but conflicts with current state - a stale version, a duplicate key. */
    CONFLICT,

    /** A business rule forbids it. The request is well-formed and the state is known. */
    BUSINESS_RULE,

    /** The caller is not identified. */
    UNAUTHENTICATED,

    /** The caller is identified and not permitted. */
    FORBIDDEN,

    /** Something this service depends on failed. Not the caller's fault, and often worth a retry. */
    DEPENDENCY_FAILURE,

    /** A dependency did not answer in time. Whether the work happened is unknown - assume it might have. */
    TIMEOUT,

    /** Load shedding. The caller should back off and retry. */
    RATE_LIMITED
}
