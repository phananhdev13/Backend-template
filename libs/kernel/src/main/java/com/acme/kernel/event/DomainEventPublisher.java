package com.acme.kernel.event;

/**
 * The one way a use case announces that something happened.
 *
 * <p>Spring's own {@code ApplicationEventPublisher.publishEvent} takes an {@code Object}, which
 * made {@link DomainEvent} advisory: a record with no {@code @EventContract}, no partition key and
 * no schema file could be published, and every rule in {@code EventContractRules} keys on
 * {@code DomainEvent}, so none of them would see it. The type here is the fix - an event with no
 * declared contract does not compile rather than shipping with its semantics left to whoever
 * creates the topic.
 *
 * <p>This is an interface in the kernel, with no framework on it, so the application layer depends
 * on the shape rather than on Spring. The implementation in {@code messaging-support} delegates to
 * {@code ApplicationEventPublisher}, which keeps Spring Modulith's transactional event publication
 * exactly as ADR-0006 describes it: the publication is recorded in the caller's transaction and
 * delivered after it commits.
 */
public interface DomainEventPublisher {

    /** Records the event for publication after the current transaction commits. */
    void publish(DomainEvent event);
}
