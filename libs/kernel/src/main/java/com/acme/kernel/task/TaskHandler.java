package com.acme.kernel.task;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import com.acme.kernel.event.Idempotent;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An inbound adapter that consumes a task queue and drives a use case.
 *
 * <p>Unlike {@link com.acme.kernel.event.EventHandler}, there is no {@code group} to get
 * wrong: a classic queue's whole point is that every consumer bound to it competes for
 * the same messages, so scaling the worker out is exactly "start another instance," never
 * a topology decision made in code.
 *
 * <p>A task queue redelivers on every failure path a broker has - a crashed consumer, a
 * negative acknowledgement, a container restart mid-processing - so every handler is
 * implicitly at-least-once. There is no {@code AT_MOST_ONCE} escape hatch the way an event
 * contract has one: {@code TaskContractRules} requires {@link Idempotent} on every
 * {@code @TaskHandler}, unconditionally.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.ADAPTER, principle = "P-131")
public @interface TaskHandler {

    /** The task type this handler consumes. Its {@link TaskContract} supplies the queue name. */
    Class<? extends Task> handles();
}
