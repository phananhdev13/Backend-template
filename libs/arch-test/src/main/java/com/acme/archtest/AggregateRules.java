package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.AggregateRoot;
import com.acme.kernel.arch.OutputPort;
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

/**
 * Aggregates are consistency boundaries: P-020.
 *
 * <p>A direct reference from one aggregate root to another widens the boundary without anyone
 * deciding to. The two are now loaded together, changed together, and locked together, and the
 * transaction that was supposed to cover one entity quietly covers two.
 */
public final class AggregateRules {

    @ArchTest
    public static final ArchRule aggregatesReferenceOtherAggregatesByIdentityOnly = classes()
            .that()
            .areAnnotatedWith(AggregateRoot.class)
            .should(holdNoReferenceToAnotherAggregate())
            .allowEmptyShould(true)
            .as("aggregates reference each other by identity, never directly (P-020)")
            .because("a direct reference makes one transaction span two consistency boundaries, and "
                    + "turns every load into a join nobody asked for. "
                    + "See docs/principles/P-020-aggregate-consistency-boundaries.md");

    /**
     * Two repository ports for one aggregate means two ways to load it, and eventually two answers
     * about what it currently is.
     */
    @ArchTest
    public static void oneRepositoryPerAggregateRoot(JavaClasses classes) {
        Map<String, List<String>> byAggregate = new HashMap<>();
        List<String> aggregates = new ArrayList<>();
        classes.stream()
                .filter(type -> Annotations.has(type, AggregateRoot.class))
                .forEach(type -> aggregates.add(type.getSimpleName()));

        classes.stream()
                .filter(type -> Annotations.has(type, OutputPort.class))
                .filter(type -> type.getSimpleName().endsWith("Repository"))
                .forEach(port -> {
                    String subject = port.getSimpleName().replaceFirst("Repository$", "");
                    if (aggregates.contains(subject)) {
                        byAggregate
                                .computeIfAbsent(subject, key -> new ArrayList<>())
                                .add(port.getName());
                    }
                });

        List<String> duplicates = byAggregate.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " has " + entry.getValue())
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AssertionError("An aggregate has more than one repository port, so there is more than one "
                    + "way to load it and eventually more than one answer about what it is.\n  "
                    + String.join("\n  ", duplicates)
                    + "\n  See docs/principles/P-020-aggregate-consistency-boundaries.md");
        }
    }

    private AggregateRules() {}

    private static ArchCondition<JavaClass> holdNoReferenceToAnotherAggregate() {
        return new ArchCondition<>("hold no field typed as another aggregate root") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getAllFields().forEach(field -> {
                    JavaClass fieldType = field.getRawType().getBaseComponentType();
                    if (fieldType.equals(item) || !Annotations.has(fieldType, AggregateRoot.class)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s holds a direct reference to the aggregate %s via field '%s'. Hold its "
                                            + "identifier instead, and load it through its own repository when you "
                                            + "genuinely need it.")
                                    .formatted(item.getName(), fieldType.getSimpleName(), field.getName())));
                });
            }
        };
    }
}
