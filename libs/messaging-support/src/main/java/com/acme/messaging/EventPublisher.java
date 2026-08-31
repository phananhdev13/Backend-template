package com.acme.messaging;

import com.acme.kernel.event.DomainEvent;

/**
 * The port an application publishes events through.
 *
 * <p>There is no topic name, no partition, no serializer and no broker type in this interface, and
 * that is the whole point. Everything a message needs is already declared on the event's
 * {@link com.acme.kernel.event.EventContract}, so a call site that could pass a topic could also
 * pass the wrong one. Application code states that something happened; the implementation decides
 * where it goes.
 */
public interface EventPublisher {

    /**
     * Publishes an event under the contract declared on its type.
     *
     * <p>The partition key comes from the contract's declared component, not from the caller, so
     * two call sites publishing the same event cannot disagree about ordering.
     *
     * @param event the event to publish
     */
    void publish(DomainEvent event);

    /**
     * Deletes a key from a compacted stream.
     *
     * <p>A tombstone is the only way to say "this entity no longer exists" on a stream whose
     * retention keeps the latest value per key forever. It is deliberately a separate method: a
     * null payload passed to {@link #publish} would be indistinguishable from a bug, and on a
     * stream of facts it would mean nothing at all.
     *
     * <p>Note what a tombstone does not do. It removes the current value; copies in log segments
     * that have not been compacted yet survive for as long as the broker chooses. For a contract
     * with {@code containsPersonalData = true}, that gap is the erasure story, and it needs an
     * answer beyond this call.
     *
     * @param eventType the event type whose contract identifies the stream
     * @param partitionKey the key to delete
     * @throws UnsupportedContractException if the stream is not compacted, where a tombstone would
     *     be delivered to consumers as a null-payload message they have no rule for
     */
    void publishTombstone(Class<? extends DomainEvent> eventType, String partitionKey);
}
