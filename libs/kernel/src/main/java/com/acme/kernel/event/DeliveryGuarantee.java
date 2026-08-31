package com.acme.kernel.event;

/** What the platform promises about how many times a consumer sees a message. */
public enum DeliveryGuarantee {

    /**
     * A message may be lost, and is never repeated.
     *
     * <p>Acceptable for telemetry that is summarised anyway. Never for anything that
     * moves money or state.
     */
    AT_MOST_ONCE,

    /**
     * A message is never lost, and may be repeated.
     *
     * <p>The default, and the honest one for a distributed system. It pushes the
     * duplicate problem to the consumer, where it is solvable: handlers of such streams
     * must be {@link Idempotent}, and the build enforces that.
     */
    AT_LEAST_ONCE,

    /**
     * Each message takes effect once, achieved by de-duplicating rather than by magic.
     *
     * <p>Kafka can offer this within its own boundary via transactions and an idempotent
     * producer. It stops at the boundary: the moment a handler writes to a database or
     * calls another service, the guarantee is the handler's to keep.
     */
    EFFECTIVELY_ONCE
}
