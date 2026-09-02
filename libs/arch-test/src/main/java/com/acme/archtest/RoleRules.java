package com.acme.archtest;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.Layer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import java.util.Optional;

/**
 * Every class declares what it is: P-010.
 *
 * <p>The point is not bookkeeping. A class whose role is written down can be checked
 * against the rules of that role, found by anyone looking for its kind, and counted when
 * asking what actually implements a principle. A class without one is outside all of
 * that, and the codebase gets one of those at a time until nobody can answer where
 * anything belongs.
 */
public final class RoleRules {

    private static final String[] ARCHITECTURAL_PACKAGES = {"..domain..", "..application..", "..adapter..", "..config.."
    };

    @ArchTest
    public static final ArchRule everyClassDeclaresARole = classes()
            .that()
            .resideInAnyPackage(ARCHITECTURAL_PACKAGES)
            .and(not(Classes.exemptFromRoleAnnotation()))
            .should(declareARole())
            .allowEmptyShould(true)
            .as("every class declares an architectural role (P-010)")
            .because("a class nobody has classified cannot be governed by any rule. "
                    + "See docs/principles/P-010-annotated-architecture.md");

    @ArchTest
    public static final ArchRule rolesMatchTheirPackage = classes()
            .that(Roles.withAnyRole())
            .should(resideInThePackageOfTheirLayer())
            .allowEmptyShould(true)
            .as("a class's role matches the package it lives in (P-010)")
            .because("the directory tree is the first thing a reader navigates; a use case filed under "
                    + "adapter makes it lie. See docs/principles/P-010-annotated-architecture.md");

    /**
     * A class may carry more than one role - {@code @EventHandler} with
     * {@code @InboundAdapter(MESSAGING)} is the intended pairing for a listener - but they must
     * place it in the same layer.
     *
     * <p>Without this, {@code Roles.layerOf} had to pick one of several by iteration order over a
     * {@code Set}, so {@code rolesMatchTheirPackage} and {@code dependenciesPointInwards} could
     * reach different verdicts on the same code. Requiring agreement makes the question have one
     * answer rather than making the tie-break deterministic and still arbitrary.
     */
    @ArchTest
    public static final ArchRule rolesAgreeOnTheirLayer = classes()
            .that(Roles.withAnyRole())
            .should(declareRolesInOneLayer())
            .allowEmptyShould(true)
            .as("a class's roles agree on the layer it is in (P-010)")
            .because("two roles placing one class in two layers leaves every layering rule to pick "
                    + "one of them arbitrarily. See docs/principles/P-010-annotated-architecture.md");

    private RoleRules() {}

    private static ArchCondition<JavaClass> declareRolesInOneLayer() {
        return new ArchCondition<>("declare roles that agree on one layer") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                var byLayer = Roles.roleAnnotationsOf(item).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                role -> Annotations.find(role, com.acme.kernel.arch.ArchRole.class)
                                        .map(archRole -> Annotations.enumName(archRole, "layer", Layer.DOMAIN.name()))
                                        .orElse(Layer.DOMAIN.name()),
                                java.util.TreeMap::new,
                                java.util.stream.Collectors.mapping(
                                        JavaClass::getSimpleName, java.util.stream.Collectors.toList())));
                if (byLayer.size() <= 1) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s carries roles from more than one layer: %s. Every layering rule then has "
                                        + "to guess which one governs it. Keep the roles that agree and "
                                        + "split the rest into their own class.")
                                .formatted(item.getName(), byLayer)));
            }
        };
    }

    private static ArchCondition<JavaClass> declareARole() {
        return new ArchCondition<>("declare an architectural role") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Roles.declaresRole(item)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s declares no architectural role. Annotate it with the role it plays "
                                        + "(@AggregateRoot, @ValueObject, @UseCase, @InputPort, @OutputPort, "
                                        + "@InboundAdapter, @OutboundAdapter, @EventHandler, @ReadModel, "
                                        + "@ArchConfig), or move it out of %s if it plays none.")
                                .formatted(item.getName(), item.getPackageName())));
            }
        };
    }

    /** The package segment a layer's classes live in. CONFIGURATION is spelt `config`. */
    private static String packageSegmentFor(Layer layer) {
        return layer == Layer.CONFIGURATION ? "config" : layer.name().toLowerCase(Locale.ROOT);
    }

    private static ArchCondition<JavaClass> resideInThePackageOfTheirLayer() {
        return new ArchCondition<>("reside in the package matching its layer") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<Layer> layer = Roles.layerOf(item);
                if (layer.isEmpty()) {
                    return;
                }
                String expected = packageSegmentFor(layer.get());
                String packageName = item.getPackageName();
                if (packageName.contains("." + expected + ".") || packageName.endsWith("." + expected)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s is annotated as %s but lives in %s, which is not a '%s' package."
                                .formatted(item.getName(), layer.get(), packageName, expected)));
            }
        };
    }
}
