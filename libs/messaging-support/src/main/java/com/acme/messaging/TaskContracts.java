package com.acme.messaging;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@link TaskContract} declarations, once per task type.
 *
 * <p>Reflection on the publish path is a cost paid per message unless someone caches it, so this
 * caches it - the same trade {@link EventContracts} makes for events. The cache is keyed by class
 * and never invalidated, which is correct because an annotation cannot change after the class is
 * loaded.
 */
public final class TaskContracts {

    private static final Map<Class<?>, TaskDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    private TaskContracts() {}

    /**
     * Parses the contract declared on a task type.
     *
     * @param taskType the annotated task type
     * @return its parsed contract
     * @throws IllegalArgumentException if the type carries no {@link TaskContract}; a task with
     *     no declared queue leaves that decision to whoever creates one by hand, which is the
     *     failure this module exists to prevent
     */
    public static TaskDescriptor describe(Class<? extends Task> taskType) {
        TaskDescriptor cached = DESCRIPTORS.get(taskType);
        if (cached != null) {
            return cached;
        }
        TaskContract contract = taskType.getAnnotation(TaskContract.class);
        if (contract == null) {
            throw new IllegalArgumentException(("%s carries no @TaskContract, so this module has nothing "
                            + "to provision or publish to. Annotate it with the queue name. "
                            + "See docs/principles/P-131-task-queues.md")
                    .formatted(taskType.getName()));
        }
        TaskDescriptor descriptor = new TaskDescriptor(contract.queue(), contract.containsPersonalData(), taskType);
        DESCRIPTORS.put(taskType, descriptor);
        return descriptor;
    }
}
