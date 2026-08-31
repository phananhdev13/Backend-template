package com.acme.messaging;

import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
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
 * Every {@link EventContract} this application can see, found by scanning.
 *
 * <p>Scanning rather than registration is a deliberate trade. A registry someone has to remember
 * to add to is a registry that is one merge behind the code: the event exists, the topic does not,
 * and the first message goes to an auto-created topic carrying the broker's defaults - which is
 * the exact drift this module exists to stop. Discovery makes the declaration itself sufficient.
 *
 * <p>Construction is also where two events claiming the same stream and version are caught. That
 * is a whole-classpath property no single class can check, and its consequence is bad enough to
 * refuse startup: one physical stream would carry two payload shapes, and every consumer would
 * fail on whichever message arrived second.
 */
public final class ContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(ContractRegistry.class);

    private final List<StreamDescriptor> descriptors;

    private final Map<String, StreamDescriptor> byIdentifier;

    /**
     * Scans the given packages and parses every contract found.
     *
     * @param basePackages packages to scan; empty means this application publishes nothing, which
     *     is a legitimate state and not worth forcing a service to configure around
     * @throws UnsupportedContractException if two event types claim the same stream and version
     */
    public ContractRegistry(List<String> basePackages) {
        List<StreamDescriptor> found = scan(basePackages);
        Map<String, StreamDescriptor> index = new LinkedHashMap<>();
        Map<String, List<String>> claimants = new TreeMap<>();
        for (StreamDescriptor descriptor : found) {
            String identifier = identifierOf(descriptor.stream(), descriptor.version());
            index.putIfAbsent(identifier, descriptor);
            claimants
                    .computeIfAbsent(identifier, key -> new ArrayList<>())
                    .add(descriptor.eventType().getName());
        }
        rejectCollisions(claimants);
        this.descriptors = List.copyOf(found);
        this.byIdentifier = Map.copyOf(index);
        log.info("Discovered {} event contract(s) in {}", this.descriptors.size(), basePackages);
    }

    /**
     * All discovered contracts, in scan order.
     *
     * @return an unmodifiable list of every contract found
     */
    public List<StreamDescriptor> all() {
        return descriptors;
    }

    /**
     * Looks up a contract by the pair that identifies a stream.
     *
     * <p>Version is part of the lookup because two versions of one logical stream coexist through
     * every incompatible migration; the name alone would be ambiguous exactly when it matters.
     *
     * @param stream logical stream name
     * @param version contract version
     * @return the contract, or empty if this application does not declare it
     */
    public Optional<StreamDescriptor> byStream(String stream, int version) {
        return Optional.ofNullable(byIdentifier.get(identifierOf(stream, version)));
    }

    private static String identifierOf(String stream, int version) {
        return stream + ".v" + version;
    }

    private static List<StreamDescriptor> scan(List<String> basePackages) {
        List<StreamDescriptor> found = new ArrayList<>();
        if (basePackages == null || basePackages.isEmpty()) {
            return found;
        }
        // useDefaultFilters=false: event records are domain types, not Spring components, and must
        // not become beans. This provider is used purely to enumerate annotated classes.
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(EventContract.class));
        ClassLoader classLoader = ContractRegistry.class.getClassLoader();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                describeCandidate(candidate.getBeanClassName(), classLoader).ifPresent(found::add);
            }
        }
        return found;
    }

    private static Optional<StreamDescriptor> describeCandidate(String className, ClassLoader classLoader) {
        if (className == null) {
            return Optional.empty();
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            // A type on the scan path that will not load is not ours to report on; whatever
            // actually needs it will fail with a better message than we could produce here.
            log.debug("Skipping {} while scanning for event contracts", className, ex);
            return Optional.empty();
        }
        if (!DomainEvent.class.isAssignableFrom(type)) {
            // @EventContract on a non-event is meaningless rather than dangerous - there is no
            // payload to publish - so warn rather than fail, and let someone delete the annotation.
            log.warn(
                    "{} is annotated @EventContract but does not implement DomainEvent, so no stream "
                            + "will be provisioned for it.",
                    className);
            return Optional.empty();
        }
        return Optional.of(EventContracts.describe(type.asSubclass(DomainEvent.class)));
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
        throw new UnsupportedContractException(("Two event types claim the same stream and version, so one "
                        + "physical stream would carry two payload shapes and consumers could not tell them "
                        + "apart.%s%n%nResolve it one of two ways: give the incompatible event a new "
                        + "version() and let the two streams run in parallel until consumers migrate, or "
                        + "rename one stream() if these are genuinely different streams that were named "
                        + "alike by accident.")
                .formatted(detail.toString()));
    }
}
