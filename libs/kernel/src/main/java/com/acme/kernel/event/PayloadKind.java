package com.acme.kernel.event;

/** Whether a message states a change or a current value. */
public enum PayloadKind {

    /**
     * Something that happened. Meaningful only in sequence with its neighbours.
     *
     * <p>Cannot be compacted: dropping superseded facts destroys the sequence.
     */
    FACT,

    /**
     * The complete current state of an entity at a point in time.
     *
     * <p>Self-contained, so a consumer that sees only the latest one per key is correct.
     * This is the only payload shape {@link StreamRetention#COMPACTED} is valid for.
     */
    STATE_SNAPSHOT
}
