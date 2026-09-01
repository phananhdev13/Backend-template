package com.acme.messaging;

import com.acme.kernel.task.Task;
import org.springframework.amqp.core.AmqpTemplate;

/**
 * Submits tasks over RabbitMQ, addressed by queue name alone.
 *
 * <p>There is no exchange to declare: publishing with the routing key set to the queue's own name
 * and no exchange specified uses the broker's default (nameless) exchange, which every queue is
 * bound to automatically under its own name. That is the whole topology a point-to-point queue
 * needs - one name, no bindings to keep in step with it.
 */
public final class RabbitTaskPublisher implements TaskPublisher {

    private final AmqpTemplate template;

    public RabbitTaskPublisher(AmqpTemplate template) {
        this.template = template;
    }

    @Override
    public void submit(Task task) {
        TaskDescriptor descriptor = TaskContracts.describe(task.getClass().asSubclass(Task.class));
        template.convertAndSend(descriptor.queue(), task);
    }
}
