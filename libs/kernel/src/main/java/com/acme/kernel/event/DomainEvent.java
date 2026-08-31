package com.acme.kernel.event;

import java.time.Instant;

/**
 * Something that happened, stated in the past tense and no longer up for negotiation.
 *
 * <p>Implementations are records in the domain layer. They carry the facts a consumer
 * needs, not a reference to the aggregate that produced them: an event that ships an
 * entity is a distributed pointer into someone else's database.
 */
public interface DomainEvent {

    /**
     * When the fact became true in the domain.
     *
     * <p>Not when it was published, serialised or consumed. Consumers that reorder or
     * de-duplicate rely on this being the domain's clock, so it is set at construction
     * and never rewritten in transit.
     */
    Instant occurredAt();
}
