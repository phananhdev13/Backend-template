package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.OutputPort;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Ports are the application's vocabulary, not the technology's: P-031.
 *
 * <p>A port that mentions a {@code ResultSet}, a {@code ConsumerRecord} or an HTTP status has
 * already chosen its implementation. Substituting it in a test then means reproducing that
 * technology's types, which is the cost the port existed to avoid.
 */
public final class PortRules {

    private static final List<String> INFRASTRUCTURE_PREFIXES = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "jakarta.servlet.",
            "java.sql.",
            "javax.sql.",
            "org.apache.kafka.",
            "com.rabbitmq.",
            "tools.jackson.",
            "com.fasterxml.jackson.",
            "org.hibernate.");

    @ArchTest
    public static final ArchRule outputPortsAreInterfaces = classes()
            .that()
            .areAnnotatedWith(OutputPort.class)
            .should()
            .beInterfaces()
            .allowEmptyShould(true)
            .as("an output port is an interface (P-031)")
            .because("an outbound adapter substitutes for the port in a test only if the port is a "
                    + "protocol rather than an implementation - a class in that position is already "
                    + "one implementation, and a second one has to extend it instead of standing "
                    + "beside it. See docs/principles/P-031-dependencies-point-inwards.md");

    @ArchTest
    public static final ArchRule outputPortsSpeakDomainLanguage = classes()
            .that()
            .areAnnotatedWith(OutputPort.class)
            .should(mentionNoInfrastructureTypes())
            .allowEmptyShould(true)
            .as("output ports mention no infrastructure types (P-031)")
            .because("a port that names a driver's type has chosen its implementation, and can only "
                    + "be substituted by reproducing that driver. "
                    + "See docs/principles/P-031-dependencies-point-inwards.md");

    /**
     * A port nobody implements is either dead or a plan. Both are worth knowing about, and neither
     * should survive a merge unnoticed.
     */
    @ArchTest
    public static void everyOutputPortHasAnImplementation(JavaClasses classes) {
        List<JavaClass> ports = classes.stream()
                .filter(type -> Annotations.has(type, OutputPort.class))
                .toList();
        List<String> unimplemented = new ArrayList<>();
        for (JavaClass port : ports) {
            boolean implemented = classes.stream()
                    .filter(candidate -> !candidate.equals(port))
                    .anyMatch(candidate -> candidate.getAllRawInterfaces().contains(port));
            if (!implemented) {
                unimplemented.add(port.getName());
            }
        }
        if (!unimplemented.isEmpty()) {
            throw new AssertionError("These output ports have no implementation in this service, so the use cases "
                    + "that depend on them cannot run:\n  " + String.join("\n  ", unimplemented)
                    + "\n  Add an @OutboundAdapter, or delete the port. "
                    + "See docs/principles/P-041-outbound-adapters-one-port.md");
        }
    }

    private PortRules() {}

    private static ArchCondition<JavaClass> mentionNoInfrastructureTypes() {
        return new ArchCondition<>("mention no infrastructure types in their signatures") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getMethods().forEach(method -> {
                    List<JavaClass> mentioned = new ArrayList<>(method.getRawParameterTypes());
                    mentioned.add(method.getRawReturnType());
                    mentioned.stream()
                            .map(JavaClass::getBaseComponentType)
                            .filter(PortRules::isInfrastructure)
                            .forEach(leaked -> events.add(SimpleConditionEvent.violated(
                                    item,
                                    "%s.%s mentions %s. Express it in domain terms instead."
                                            .formatted(item.getName(), method.getName(), leaked.getName()))));
                });
            }
        };
    }

    private static boolean isInfrastructure(JavaClass type) {
        return INFRASTRUCTURE_PREFIXES.stream()
                .anyMatch(prefix -> type.getFullName().startsWith(prefix));
    }
}
