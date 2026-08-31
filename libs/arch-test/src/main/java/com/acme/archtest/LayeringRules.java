package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.Layer;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import java.util.Optional;

/**
 * Dependencies point inwards: P-031.
 *
 * <p>Checked twice on purpose, because the two checks fail on different mistakes. The
 * package rule catches code put in the wrong directory. The role rule catches code in
 * the right directory doing the wrong thing - an application class reaching a technology
 * because the adapter that should have hidden it lives in the same package tree.
 */
public final class LayeringRules {

    /** Packages the domain may never reach, because reaching them makes it untestable in isolation. */
    private static final String[] INFRASTRUCTURE_PACKAGES = {
        "org.springframework..",
        "jakarta.persistence..",
        "jakarta.servlet..",
        "jakarta.ws..",
        "org.hibernate..",
        "tools.jackson..",
        "com.fasterxml.jackson..",
        "org.apache.kafka..",
        "com.rabbitmq..",
        "javax.sql..",
        "java.sql.."
    };

    @ArchTest
    public static final ArchRule packagesFormAHexagon = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("com.acme..")
            .layer("Domain")
            .definedBy("..domain..")
            .optionalLayer("Application")
            .definedBy("..application..")
            .optionalLayer("Adapter")
            .definedBy("..adapter..")
            .optionalLayer("Configuration")
            .definedBy("..config..")
            .whereLayer("Configuration")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapter")
            .mayOnlyBeAccessedByLayers("Configuration")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapter", "Configuration")
            .as("packages form a hexagon: adapter -> application -> domain (P-031)")
            .because("a dependency that points outwards drags a technology choice into the code that "
                    + "should outlive it. See docs/principles/P-031-dependencies-point-inwards.md");

    @ArchTest
    public static final ArchRule dependenciesPointInwards = classes()
            .that(Roles.withAnyRole())
            .should(onlyDependOnPermittedLayers())
            .allowEmptyShould(true)
            .as("annotated roles respect the layer dependency rule (P-031)")
            .because("Layer.mayDependOn in libs/kernel is the single definition of this rule; "
                    + "see docs/principles/P-031-dependencies-point-inwards.md");

    @ArchTest
    public static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRASTRUCTURE_PACKAGES)
            .allowEmptyShould(true)
            .as("the domain depends on no framework (P-020)")
            .because("domain code that needs a Spring context, a database session or a serialiser to run "
                    + "can only be tested with one. See docs/principles/P-020-aggregate-consistency-boundaries.md");

    private LayeringRules() {}

    private static ArchCondition<JavaClass> onlyDependOnPermittedLayers() {
        return new ArchCondition<>("only depend on layers their own layer may depend on") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<Layer> source = Roles.layerOf(item);
                if (source.isEmpty()) {
                    return;
                }
                Layer from = source.get();
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (target.equals(item)) {
                        continue;
                    }
                    Optional<Layer> to = Roles.layerOf(target);
                    if (to.isPresent() && !from.mayDependOn(to.get())) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                "%s (%s) depends on %s (%s): %s may not depend on %s. %s"
                                        .formatted(
                                                item.getName(),
                                                from,
                                                target.getName(),
                                                to.get(),
                                                from,
                                                to.get(),
                                                dependency.getDescription())));
                    }
                }
            }
        };
    }
}
