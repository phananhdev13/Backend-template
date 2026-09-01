package com.acme.temporal;

import com.acme.kernel.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Every {@code @WorkflowDefinition} class this service's worker registers, found by scanning -
 * the same trade {@code TaskRegistry} makes for tasks: a workflow implementation someone writes
 * but forgets to wire into a worker by hand never runs, and the first attempt to start it fails
 * with "workflow type not registered" against a worker that has been running happily the whole
 * time.
 */
public final class WorkflowDefinitions {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitions.class);

    private WorkflowDefinitions() {}

    /**
     * Scans the given packages for {@code @WorkflowDefinition} classes.
     *
     * @param basePackages packages to scan; empty means this service runs no workflows, which is
     *     a legitimate state
     */
    public static List<Class<?>> scan(List<String> basePackages) {
        List<Class<?>> found = new ArrayList<>();
        if (basePackages == null || basePackages.isEmpty()) {
            return found;
        }
        // useDefaultFilters=false: a workflow definition is never a Spring bean (kernel's own
        // Javadoc on WorkflowDefinition says why), so this provider only enumerates classes -
        // it must never be allowed to register one as a component.
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(WorkflowDefinition.class));
        ClassLoader classLoader = WorkflowDefinitions.class.getClassLoader();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                loadCandidate(candidate.getBeanClassName(), classLoader).ifPresent(found::add);
            }
        }
        log.info("Discovered {} workflow definition(s) in {}", found.size(), basePackages);
        return found;
    }

    private static Optional<Class<?>> loadCandidate(String className, ClassLoader classLoader) {
        if (className == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClassUtils.forName(className, classLoader));
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            log.debug("Skipping {} while scanning for workflow definitions", className, ex);
            return Optional.empty();
        }
    }
}
