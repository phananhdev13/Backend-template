package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.ValueObject;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * Value objects are values: P-021.
 *
 * <p>A mutable value object is shared by reference and changed by whoever holds it, so an amount
 * stored on one order changes on another. An identifier field on one means it was an entity all
 * along, and equality by attributes is now wrong.
 */
public final class ValueObjectRules {

    @ArchTest
    public static final ArchRule valueObjectsAreImmutable = classes()
            .that()
            .areAnnotatedWith(ValueObject.class)
            .should(beImmutable())
            .allowEmptyShould(true)
            .as("value objects are immutable (P-021)")
            .because("a value that can change after construction is shared by reference and mutated "
                    + "by whoever holds it. See docs/principles/P-021-illegal-states-unrepresentable.md");

    @ArchTest
    public static final ArchRule valueObjectsHaveNoIdentityField = classes()
            .that()
            .areAnnotatedWith(ValueObject.class)
            .should(declareNoIdentityField())
            .allowEmptyShould(true)
            .as("value objects have no identity (P-021)")
            .because("if two instances with equal attributes are still different things, this is an "
                    + "entity and equality by attributes is wrong. "
                    + "See docs/principles/P-021-illegal-states-unrepresentable.md");

    private ValueObjectRules() {}

    private static ArchCondition<JavaClass> beImmutable() {
        return new ArchCondition<>("be a record, or a final class with only final fields") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isRecord() || item.isEnum()) {
                    return;
                }
                List<String> problems = new java.util.ArrayList<>();
                if (!item.getModifiers().contains(JavaModifier.FINAL)) {
                    problems.add("the class is not final, so a subclass can add mutable state");
                }
                item.getFields().stream()
                        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                        .filter(field -> !field.getModifiers().contains(JavaModifier.FINAL))
                        .forEach(field -> problems.add("field '" + field.getName() + "' is not final"));
                if (problems.isEmpty()) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s is a @ValueObject but is mutable: %s. Prefer a record."
                                .formatted(item.getName(), String.join("; ", problems))));
            }
        };
    }

    private static ArchCondition<JavaClass> declareNoIdentityField() {
        return new ArchCondition<>("declare no field called id") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getFields().stream()
                        .filter(field -> field.getName().equalsIgnoreCase("id"))
                        .forEach(field -> events.add(SimpleConditionEvent.violated(
                                item,
                                ("%s is a @ValueObject with an '%s' field. A thing with identity is a "
                                                + "@DomainEntity or an @AggregateRoot.")
                                        .formatted(item.getName(), field.getName()))));
            }
        };
    }
}
