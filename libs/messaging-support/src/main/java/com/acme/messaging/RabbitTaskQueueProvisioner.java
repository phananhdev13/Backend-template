package com.acme.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Turns discovered {@link TaskDescriptor}s into the queues a broker needs to exist.
 *
 * <p>Every task gets two durable, classic queues: the queue itself, and its dead-letter
 * destination. Both are named, not configured - {@code RepublishMessageRecoverer} in
 * {@code MessagingSupportAutoConfiguration} computes {@code <queue>.dlq} from the failing
 * message's own routing key, so the two names have to agree, and deriving one from the other
 * here is what keeps them from drifting apart.
 *
 * <p>Neither queue needs an exchange or a binding. Publishing directly to the broker's default
 * exchange with the queue's own name as the routing key is enough - see {@link RabbitTaskPublisher}
 * and the recoverer's routing key expression.
 */
public final class RabbitTaskQueueProvisioner {

    /**
     * The queues every discovered task contract needs, wrapped so Spring AMQP's
     * {@code RabbitAdmin} declares all of them from a single bean.
     *
     * @param descriptors contracts to provision queues for
     * @return the task queue and dead-letter queue for each contract
     */
    public Declarables toQueues(List<TaskDescriptor> descriptors) {
        List<Declarable> declarables = new ArrayList<>();
        for (TaskDescriptor descriptor : descriptors) {
            declarables.add(classicQueue(descriptor.queue()));
            declarables.add(classicQueue(descriptor.deadLetterQueue()));
        }
        return new Declarables(declarables);
    }

    private static Queue classicQueue(String name) {
        return QueueBuilder.durable(name).classic().build();
    }
}
