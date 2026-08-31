package com.acme.kernel.event;

/**
 * How long the broker keeps a message, and on what basis it decides to drop one.
 *
 * <p>The choice follows from what the stream carries. A stream of facts is a log: facts
 * do not become untrue, and old ones age out on time. A stream of current state is a
 * table: only the latest value per key matters, and keeping the rest is waste.
 */
public enum StreamRetention {

    /**
     * Keep everything for a fixed window, then delete oldest first.
     *
     * <p>The default, and correct for facts. A consumer that falls further behind than
     * the window loses data, so the window is a recovery budget, not a storage setting.
     *
     * <p>Kafka: {@code cleanup.policy=delete}, {@code retention.ms}.
     * RabbitMQ Streams: {@code max-age}.
     */
    TIME_WINDOW,

    /**
     * Keep the latest message per key forever, discard superseded ones.
     *
     * <p>Correct only for state snapshots, and only when the message carries the whole
     * current state. Compacting a stream of deltas destroys the earlier deltas and
     * leaves consumers unable to rebuild anything - the failure appears the first time a
     * consumer replays from the beginning, long after the change that caused it.
     *
     * <p>Deletion is expressed as a tombstone: the key with a null payload.
     *
     * <p>Kafka: {@code cleanup.policy=compact}.
     * RabbitMQ Streams have no compaction; {@code libs/messaging-support} rejects this
     * combination at startup rather than silently degrading to time-based retention.
     */
    COMPACTED,

    /**
     * Compact, and also drop records older than the window.
     *
     * <p>Bounds the storage a compacted stream can occupy, at the cost of guaranteeing
     * that a full replay reconstructs current state. Choose it when the stream is a
     * cache that can be rebuilt from elsewhere.
     *
     * <p>Kafka: {@code cleanup.policy=compact,delete}.
     */
    COMPACTED_AND_WINDOWED,

    /**
     * Keep everything, forever.
     *
     * <p>For streams that are the system of record. Someone has to own the storage cost
     * and the replay story, so this requires an ADR reference on the contract.
     */
    INFINITE
}
