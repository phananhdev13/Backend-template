package com.acme.archtest;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** Conditions shared by more than one rule class. */
public final class ArchConditions {

    private ArchConditions() {}

    /** Matches a class declaring any public instance method whose name starts with {@code prefix}. */
    public static ArchCondition<JavaClass> declareAPublicMethodNamed(String prefix) {
        return new ArchCondition<>("declare a public method named " + prefix + "*") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getMethods().stream()
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                        .filter(method -> method.getName().startsWith(prefix))
                        .forEach(method -> events.add(SimpleConditionEvent.satisfied(
                                item, "%s declares %s".formatted(item.getName(), method.getName()))));
            }
        };
    }
}
