package com.acme.grpc;

import com.acme.kernel.error.ErrorKind;
import io.grpc.Status;

/**
 * The single mapping from a domain failure category to a gRPC status, the RPC-transport twin of
 * {@code web-support}'s {@code ErrorKindStatus}.
 *
 * <p>Kept here, and nowhere else, so that a use case never has to know it is being called over
 * gRPC any more than it knows it is being called over HTTP. Every entry is chosen for what it
 * tells the caller to do next - gRPC's {@link Status.Code} vocabulary is coarser than HTTP's, so
 * two {@link ErrorKind}s that get different HTTP statuses can legitimately share a gRPC one.
 */
public final class ErrorKindGrpcStatus {

    private ErrorKindGrpcStatus() {}

    /** The status that corresponds to a failure category. */
    public static Status of(ErrorKind kind) {
        return switch (kind) {
            case VALIDATION -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            // ABORTED, not FAILED_PRECONDITION: a stale version or a duplicate key is a
            // concurrency conflict the caller can retry against fresh state, which is exactly
            // what ABORTED means in gRPC's vocabulary.
            case CONFLICT -> Status.ABORTED;
            // FAILED_PRECONDITION: the request was understood and the state is known, but the
            // system's current state forbids it - retrying unchanged will not help.
            case BUSINESS_RULE -> Status.FAILED_PRECONDITION;
            case UNAUTHENTICATED -> Status.UNAUTHENTICATED;
            case FORBIDDEN -> Status.PERMISSION_DENIED;
            case DEPENDENCY_FAILURE -> Status.UNAVAILABLE;
            case TIMEOUT -> Status.DEADLINE_EXCEEDED;
            case RATE_LIMITED -> Status.RESOURCE_EXHAUSTED;
        };
    }
}
