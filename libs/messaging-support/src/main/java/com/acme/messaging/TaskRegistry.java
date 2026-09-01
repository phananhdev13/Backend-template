package com.acme.messaging;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Every {@link TaskContract} this application can see, found by scanning.
 *
 * <p>The same trade {@link ContractRegistry} makes for events: a task someone declares but forgets
 * to register in a hand-maintained list is a queue that never gets created, and the first
 * publish either fails outright or - worse, if the broker auto-creates queues - succeeds into a
 * queue with none of the settings the task actually needs.
 */
public final class TaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

    private final List<TaskDescriptor> descriptors;

    private final Map<String, TaskDescriptor> byQueue;

    /**
     * Scans the given packages and parses every contract found.
     *
     * @param basePackages packages to scan; empty means this application submits no tasks, which
     *     is a legitimate state and not worth forcing a service to configure around
     * @throws UnsupportedContractException if two task types claim the same queue
     */
    public TaskRegistry(List<String> basePackages) {
        List<TaskDescriptor> found = scan(basePackages);
        Map<String, TaskDescriptor> index = new LinkedHashMap<>();
        Map<String, List<String>> claimants = new TreeMap<>();
        for (TaskDescriptor descriptor : found) {
            index.putIfAbsent(descriptor.queue(), descriptor);
            claimants
                    .computeIfAbsent(descriptor.queue(), key -> new ArrayList<>())
                    .add(descriptor.taskType().getName());
        }
        rejectCollisions(claimants);
        this.descriptors = List.copyOf(found);
        this.byQueue = Map.copyOf(index);
        log.info("Discovered {} task contract(s) in {}", this.descriptors.size(), basePackages);
    }

    /** All discovered contracts, in scan order. */
    public List<TaskDescriptor> all() {
        return descriptors;
    }

    /** Looks up a contract by its queue name. */
    public Optional<TaskDescriptor> byQueue(String queue) {
        return Optional.ofNullable(this.byQueue.get(queue));
    }

    private static List<TaskDescriptor> scan(List<String> basePackages) {
        List<TaskDescriptor> found = new ArrayList<>();
        if (basePackages == null || basePackages.isEmpty()) {
            return found;
        }
        // useDefaultFilters=false: task records are domain types, not Spring components, and
        // must not become beans. This provider is used purely to enumerate annotated classes.
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(TaskContract.class));
        ClassLoader classLoader = TaskRegistry.class.getClassLoader();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                describeCandidate(candidate.getBeanClassName(), classLoader).ifPresent(found::add);
            }
        }
        return found;
    }

    private static Optional<TaskDescriptor> describeCandidate(String className, ClassLoader classLoader) {
        if (className == null) {
            return Optional.empty();
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            // A type on the scan path that will not load is not ours to report on; whatever
            // actually needs it will fail with a better message than we could produce here.
            log.debug("Skipping {} while scanning for task contracts", className, ex);
            return Optional.empty();
        }
        if (!Task.class.isAssignableFrom(type)) {
            // @TaskContract on a non-task is meaningless rather than dangerous - there is no
            // payload to publish - so warn rather than fail, and let someone delete the annotation.
            log.warn(
                    "{} is annotated @TaskContract but does not implement Task, so no queue will be "
                            + "provisioned for it.",
                    className);
            return Optional.empty();
        }
        return Optional.of(TaskContracts.describe(type.asSubclass(Task.class)));
    }

    private static void rejectCollisions(Map<String, List<String>> claimants) {
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : claimants.entrySet()) {
            if (entry.getValue().size() > 1) {
                detail.append("%n  %s is claimed by %s".formatted(entry.getKey(), String.join(", ", entry.getValue())));
            }
        }
        if (detail.isEmpty()) {
            return;
        }
        throw new UnsupportedContractException(
                ("Two task types claim the same queue, so a consumer cannot tell which contract "
                                + "governs it.%s%n%nGive one of them its own queue.")
                        .formatted(detail.toString()));
    }
}
