package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.arch.OutputPort;
import com.acme.kernel.event.EventHandler;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
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

/**
 * Adapters translate: P-040, P-041.
 *
 * <p>A controller that reaches a repository directly has skipped the use case, and with it the
 * transaction boundary and the authorisation check. Nothing about the code says so, and the same
 * request through a different entry point behaves differently.
 */
public final class AdapterRules {

    @ArchTest
    public static final ArchRule inboundAdaptersOnlyCallInputPorts = noClasses()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .or()
            .areAnnotatedWith(EventHandler.class)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(OutputPort.class)
            .allowEmptyShould(true)
            .as("inbound adapters reach the application only through input ports (P-040)")
            .because("a controller calling a repository skips the use case, and with it the "
                    + "transaction and the authorisation check. "
                    + "See docs/principles/P-040-inbound-adapters-translate.md");

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
