package com.acme.agentfactory.registry.adapter.out.messaging;

import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Carries a committed {@code AgentVersionActivated} out to the broker - the same outbox shape as
 * {@code OrderEventPublisherAdapter}. The use case published inside its own transaction; Spring
 * Modulith recorded the pending publication in that same commit, and calls this listener
 * afterwards, so an activation that rolls back never reaches a consumer as an announcement.
 */
@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(
        value = {"P-051", "P-072"},
        note = "Producer delivery timeout and retries are set in application.yml; unpublished events "
                + "remain in the Modulith event publication registry and are retried on restart.")
public class AgentEventPublisherAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisherAdapter.class);

    private final EventPublisher publisher;

    AgentEventPublisherAdapter(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @ApplicationModuleListener
    public void on(AgentVersionActivated event) {
        publisher.publish(event);
        log.debug(
                "Forwarded AgentVersionActivated agentId={} version={} to the broker",
                event.agentId(),
                event.version());
    }
}
