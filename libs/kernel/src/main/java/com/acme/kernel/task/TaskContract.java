package com.acme.kernel.task;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The published contract of a background task queue, declared on the task that flows
 * through it.
 *
 * <p>A classic queue has no partition key and no retention policy to choose - a message
 * is delivered to one consumer and is gone, so there is nothing to compact and no reader
 * ever arrives to find it already deleted. What is left to declare is the queue's identity
 * and whether it carries data that changes how its dead letters must be handled; how hard
 * to retry before giving up is a deployment-shaped decision, set once for every queue in
 * {@code acme.messaging.rabbit}, the same way Kafka's redelivery budget is not per-event.
 *
 * <p>{@code libs/messaging-support} is the only code that reads this at runtime. It
 * provisions the RabbitMQ queue and its dead-letter destination from it, so a new task
 * type gets both without an operator creating either by hand.
 *
 * <p>See {@code docs/principles/P-131-task-queues.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-131")
public @interface TaskContract {

    /**
     * Logical queue name in {@code <context>.<task>} form, for example
     * {@code "agents.provision-deployment"}.
     *
     * <p>Exactly one {@link Task} type may claim a given queue -
     * {@code TaskContractRules.queueNamesAreUnique} rejects two types sharing one, because
     * the provisioner and the handler both key off this name to find each other.
     */
    String queue();

    /**
     * Whether the task payload carries personal data.
     *
     * <p>A task that exhausts its retries is not discarded - it lands on the dead-letter
     * queue, where it sits until someone looks at it. Personal data sitting there past the
     * exhausted attempts is a retention decision, not an accident, so this combination
     * requires an {@code @Adr}.
     */
    boolean containsPersonalData() default false;
}
