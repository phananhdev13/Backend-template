package com.acme.kernel.task;

import java.time.Instant;

/**
 * Work to be done once, by exactly one consumer, and then forgotten.
 *
 * <p>A {@link com.acme.kernel.event.DomainEvent} is a fact broadcast to every interested
 * consumer, kept for as long as its stream's retention says. A task is the opposite
 * shape: point-to-point, competing consumers, and gone the moment one of them finishes
 * it. There is no replay - a task queue is not a system of record, and nothing should
 * ever be recovered by re-reading one. Reach for a task when the work is "do this job
 * once, somewhere," and for an event when the work is "tell everyone this happened."
 *
 * <p>Implementations are records, carrying what the handler needs to do the work rather
 * than a reference to the aggregate that requested it, for the same reason an event does
 * not carry one: the queue outlives the request that enqueued it.
 */
public interface Task {

    /**
     * When the task was handed to the queue.
     *
     * <p>Not when a consumer picks it up - that is retry- and scheduling-dependent and
     * belongs in transport metadata, not on the task itself.
     */
    Instant submittedAt();
}
