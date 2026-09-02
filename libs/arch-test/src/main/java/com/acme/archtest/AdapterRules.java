package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.Adr;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.arch.OutputPort;
import com.acme.kernel.arch.ValueObject;
import com.acme.kernel.event.EventHandler;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapters translate: P-040, P-041.
 *
 * <p>A controller that reaches a repository directly has skipped the use case, and with it the
 * transaction boundary and the authorisation check. Nothing about the code says so, and the same
 * request through a different entry point behaves differently.
 */
public final class AdapterRules {

    /**
     * Data access this rule must see even though it carries no role.
     *
     * <p>The {@code @OutputPort} check alone was not enough, and the gap was exactly the example
     * in this rule's own {@code because()}: {@code Classes.exemptFromRoleAnnotation} excuses a
     * {@code *Repository} interface from carrying a role at all, so a Spring Data repository is
     * not {@code @OutputPort}, has no layer, and was invisible to every layering rule. A
     * controller could inject one and call {@code deleteById} directly - skipping the use case,
     * its transaction and its authorisation check - with a green build.
     */
    private static final List<String> DATA_ACCESS_TYPES = List.of(
            "jakarta.persistence.EntityManager",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository");

    @ArchTest
    public static final ArchRule inboundAdaptersOnlyCallInputPorts = noClasses()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .or()
            .areAnnotatedWith(EventHandler.class)
            .should()
            .dependOnClassesThat(annotatedWithOutputPort().or(dataAccess()))
            .allowEmptyShould(true)
            .as("inbound adapters reach the application only through input ports (P-040)")
            .because("a controller calling a repository skips the use case, and with it the "
                    + "transaction and the authorisation check. "
                    + "See docs/principles/P-040-inbound-adapters-translate.md");

    private static DescribedPredicate<JavaClass> annotatedWithOutputPort() {
        return new DescribedPredicate<>("an @OutputPort") {
            @Override
            public boolean test(JavaClass type) {
                return Annotations.has(type, OutputPort.class);
            }
        };
    }

    private static DescribedPredicate<JavaClass> dataAccess() {
        return new DescribedPredicate<>("a repository or a data-access template") {
            @Override
            public boolean test(JavaClass type) {
                return type.getSimpleName().endsWith("Repository")
                        || DATA_ACCESS_TYPES.contains(type.getFullName())
                        || DATA_ACCESS_TYPES.stream().anyMatch(type::isAssignableTo);
            }
        };
    }

    /**
     * A cyclomatic-complexity threshold on adapter methods, the other half of this rule as P-040
     * originally specified it, is deliberately not attempted here: ArchUnit's imported model gives
     * dependencies and calls, not a control-flow graph, so "count the decision points in this
     * method" is not something its API answers honestly. Checkstyle's own general-purpose
     * complexity check is the backstop for that; this narrower, mechanically sound half catches the
     * concrete failure mode P-040's own "wrong" example shows.
     */
    @ArchTest
    public static final ArchRule inboundAdaptersContainNoBusinessLogic = classes()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .should(compareNoValueObjectAgainstAThreshold())
            .allowEmptyShould(true)
            .as("inbound adapters compare no value object against a threshold (P-040)")
            .because("request.total().compareTo(customer.creditLimit()) in a controller is a business "
                    + "rule reachable only from this one entry point - a Kafka handler or a CSV import "
                    + "that creates the same kind of order never sees it. "
                    + "See docs/principles/P-040-inbound-adapters-translate.md");

    private static final Set<String> THRESHOLD_COMPARISON_METHODS =
            Set.of("compareTo", "isGreaterThan", "isLessThan", "isGreaterThanOrEqualTo", "isLessThanOrEqualTo");

    private static ArchCondition<JavaClass> compareNoValueObjectAgainstAThreshold() {
        return new ArchCondition<>("compare no @ValueObject against a threshold") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, Adr.class)) {
                    return;
                }
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    String methodName = call.getTarget().getName();
                    if (!THRESHOLD_COMPARISON_METHODS.contains(methodName)
                            || !Annotations.has(owner, ValueObject.class)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s calls %s.%s(...), comparing a value object against a threshold. That "
                                            + "is a business decision - move it behind the input port so "
                                            + "every entry point gets it, not only this one.")
                                    .formatted(item.getName(), owner.getSimpleName(), methodName)));
                }
            }
        };
    }

    @ArchTest
    public static final ArchRule outboundAdaptersImplementTheirDeclaredPort = classes()
            .that()
            .areAnnotatedWith(OutboundAdapter.class)
            .should(implementTheDeclaredPort())
            .allowEmptyShould(true)
            .as("an outbound adapter implements the port it declares (P-041)")
            .because("the declaration is what lets a reader go from a port to its implementations "
                    + "and back; one that does not hold makes the hexagon unnavigable. "
                    + "See docs/principles/P-041-outbound-adapters-one-port.md");

    @ArchTest
    public static final ArchRule adaptersDoNotDependOnOtherAdapters = classes()
            .that()
            .resideInAPackage("..adapter..")
            .should(notReachIntoAnotherModulesAdapters())
            .allowEmptyShould(true)
            .as("adapters do not call other modules' adapters (P-100)")
            .because("two adapters wired together bypass both modules' application layers and couple "
                    + "two technologies that were meant to be independent. "
                    + "See docs/principles/P-100-vertical-slice-modules.md");

    /**
     * Two adapters for one port and one technology means two implementations of the same contract,
     * and a wiring choice nobody documented deciding which runs.
     */
    @ArchTest
    public static void oneAdapterPerPortPerKind(JavaClasses classes) {
        Map<String, List<String>> byPortAndKind = new HashMap<>();
        classes.stream()
                .filter(type -> Annotations.has(type, OutboundAdapter.class))
                .forEach(adapter -> Annotations.find(adapter, OutboundAdapter.class)
                        .ifPresent(annotation -> {
                            String port = Annotations.type(annotation, "port")
                                    .map(JavaClass::getSimpleName)
                                    .orElse("?");
                            String kind = Annotations.enumName(annotation, "kind", "?");
                            byPortAndKind
                                    .computeIfAbsent(port + " over " + kind, key -> new ArrayList<>())
                                    .add(adapter.getName());
                        }));
        List<String> duplicates = byPortAndKind.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " is implemented by " + entry.getValue())
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AssertionError("A port has more than one adapter of the same kind, so which one runs is a "
                    + "wiring accident:\n  " + String.join("\n  ", duplicates)
                    + "\n  See docs/principles/P-041-outbound-adapters-one-port.md");
        }
    }

    private AdapterRules() {}

    private static ArchCondition<JavaClass> implementTheDeclaredPort() {
        return new ArchCondition<>("implement the port named in its @OutboundAdapter") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> declared =
                        Annotations.find(item, OutboundAdapter.class).flatMap(a -> Annotations.type(a, "port"));
                if (declared.isEmpty()) {
                    return;
                }
                JavaClass port = declared.get();
                // Only local @OutputPort contracts are checked. An adapter may legitimately be
                // declared against a platform interface it reacts to rather than implements.
                if (!Annotations.has(port, OutputPort.class)) {
                    return;
                }
                if (item.getAllRawInterfaces().contains(port)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s declares port = %s but does not implement it."
                                .formatted(item.getName(), port.getSimpleName())));
            }
        };
    }

    private static ArchCondition<JavaClass> notReachIntoAnotherModulesAdapters() {
        return new ArchCondition<>("not depend on another feature module's adapters") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String owner = BoundaryRules.moduleOf(item);
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (!target.getPackageName().contains(".adapter.")) {
                        continue;
                    }
                    if (BoundaryRules.moduleOf(target).equals(owner)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "%s depends on %s, an adapter of another feature module. Go through that "
                                            .formatted(item.getName(), target.getName())
                                    + "module's @PublicApi, or react to one of its events."));
                }
            }
        };
    }
}
