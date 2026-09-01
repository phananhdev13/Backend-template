package com.acme.messaging;

import com.acme.kernel.task.Task;

/**
 * The port an application submits background tasks through.
 *
 * <p>There is no queue name and no broker type in this interface, and that is the whole point.
 * The queue comes from the task's own {@link com.acme.kernel.task.TaskContract}, so a call site
 * that could pass a queue name could also pass the wrong one. Application code states that a job
 * needs doing; the implementation decides where it goes.
 */
public interface TaskPublisher {

    /**
     * Submits a task to the queue declared on its type.
     *
     * @param task the task to submit
     */
    void submit(Task task);
}
