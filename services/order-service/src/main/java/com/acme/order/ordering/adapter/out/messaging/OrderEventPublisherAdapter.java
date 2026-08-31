package com.acme.order.ordering.adapter.out.messaging;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.messaging.EventPublisher;
import com.acme.order.ordering.domain.OrderPlaced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Carries a committed domain event out to the broker.
 *
 * <p>This is the second half of the outbox. The use case published to the application context
 * inside its transaction; Spring Modulith recorded the pending publication in the same commit and
 * calls this listener afterwards. If the broker is down, the publication stays recorded and is
 * retried - the state change and the announcement cannot end up disagreeing.
 *
 * <p>Nothing here decides anything. The stream, the key and the retention all come from the
 * {@code @EventContract} on {@link OrderPlaced}.
 */
@OutboundAdapter(port = EventPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(
        value = {"P-051", "P-072"},
        note = "Producer delivery timeout and retries are set in application.yml; unpublished events "
                + "remain in the Modulith event publication registry and are retried on restart.")
public class OrderEventPublisherAdapter {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisherAdapter.class);

    private final EventPublisher publisher;

    OrderEventPublisherAdapter(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @ApplicationModuleListener
    public void on(OrderPlaced event) {
        publisher.publish(event);
        log.debug("Forwarded OrderPlaced orderId={} to the broker", event.orderId());
    }
}
