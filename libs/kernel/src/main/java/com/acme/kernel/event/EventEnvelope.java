package com.acme.kernel.event;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Transport metadata wrapped around a domain event.
 *
 * <p>The domain event says what happened. The envelope says which delivery this is,
 * which request caused it, and which contract it was published under - the things needed
 * to de-duplicate, to trace, and to reject a message published under a contract version
 * a consumer does not understand.
 *
 * @param eventId unique per logical event, stable across publish retries, so it can key
 *     de-duplication
 * @param stream logical stream name from the contract
 * @param version contract version the payload conforms to
 * @param partitionKey value of the contract's declared key for this message
 * @param occurredAt when the fact became true, copied from the payload
 * @param correlationId identifier of the originating request, carried across every hop
 * @param headers additional transport headers, including trace context
 * @param payload the event itself, or null for a tombstone on a compacted stream
 */
public record EventEnvelope<T extends DomainEvent>(
        String eventId,
        String stream,
        int version,
        String partitionKey,
        Instant occurredAt,
        @Nullable String correlationId,
        Map<String, String> headers,
        @Nullable T payload) {

    /** Whether this message deletes the key from a compacted stream. */
    public boolean isTombstone() {
        return payload == null;
    }
}
