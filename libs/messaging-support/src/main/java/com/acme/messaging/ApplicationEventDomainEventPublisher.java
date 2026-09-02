package com.acme.messaging;

import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.DomainEventPublisher;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes through Spring, so Modulith's event publication registry behaves exactly as before.
 *
 * <p>The only thing this adds is the type: {@link DomainEventPublisher#publish} accepts a
 * {@link DomainEvent} where {@code ApplicationEventPublisher.publishEvent} accepts an
 * {@code Object}. See {@code DomainEventPublisher} for why that mattered.
 */
public final class ApplicationEventDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    public ApplicationEventDomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publishEvent(event);
    }
}
