package com.acme.messaging;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;

/**
 * A {@link TaskContract} read off a task type and turned into data.
 *
 * <p>The annotation is the declaration; this is the parsed form the provisioner and publisher
 * work from. Keeping the two apart means a provisioner can be handed a descriptor in a unit test
 * without an annotated class to hang it on - the same reason {@code messaging-support} keeps
 * {@link StreamDescriptor} apart from {@code @EventContract}.
 *
 * @param queue logical queue name, unique across every {@link Task} type
 * @param containsPersonalData whether the task payload carries personal data
 * @param taskType the annotated type, kept so failures can name the class a human has to edit
 */
public record TaskDescriptor(String queue, boolean containsPersonalData, Class<? extends Task> taskType) {

    /**
     * The dead-letter queue a task lands on once retries are exhausted.
     *
     * <p>A fixed suffix rather than a configured name, the same choice {@code messaging-support}
     * makes for a Kafka topic's {@code .dlq} - a name a reader can derive from the queue itself
     * needs no lookup to find.
     */
    public String deadLetterQueue() {
        return queue + ".dlq";
    }
}
