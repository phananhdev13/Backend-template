package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.ArchConfig;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Configuration is wiring: P-011.
 *
 * <p>A decision taken inside a {@code @Bean} method is a decision no unit test will ever reach,
 * because reaching it means starting a context. Logic drifts there precisely because it is
 * convenient, and it is discovered later by someone wondering why the same rule behaves
 * differently in production.
 */
public final class ConfigRules {

    private static final String SPRING_BEAN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_CONFIGURATION = "org.springframework.context.annotation.Configuration";

    @ArchTest
    public static final ArchRule configurationContainsNoBusinessLogic = classes()
            .that()
            .areAnnotatedWith(ArchConfig.class)
            .should(declareOnlyBeanFactoryMethods())
            .allowEmptyShould(true)
            .as("configuration classes declare only bean factory methods (P-011)")
            .because("logic inside a configuration class is logic no test can reach without starting "
                    + "a context. See docs/principles/P-011-configuration-is-wiring.md");

    @ArchTest
    public static final ArchRule nothingDependsOnConfiguration = noClasses()
            .that()
            .areNotAnnotatedWith(ArchConfig.class)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(ArchConfig.class)
            .allowEmptyShould(true)
            .as("nothing depends on a configuration class (P-011)")
            .because("configuration knows every layer; anything depending on it inherits that reach "
                    + "and stops being substitutable. "
                    + "See docs/principles/P-011-configuration-is-wiring.md");

    private ConfigRules() {}

    private static ArchCondition<JavaClass> declareOnlyBeanFactoryMethods() {
        return new ArchCondition<>("declare only @Bean methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // @ConfigurationProperties holders are configuration data, not wiring: their
                // accessors are the whole point and are exempt.
                boolean isSpringConfiguration = item.getAnnotations().stream()
                        .anyMatch(a -> a.getRawType().getFullName().equals(SPRING_CONFIGURATION));
                if (!isSpringConfiguration) {
                    return;
                }
                item.getMethods().stream()
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                        .filter(method -> method.getAnnotations().stream()
                                .noneMatch(a -> a.getRawType().getFullName().equals(SPRING_BEAN)))
                        .forEach(method -> events.add(SimpleConditionEvent.violated(
                                item,
                                ("%s.%s is a public method on an @ArchConfig class that is not a @Bean "
                                                + "factory. Move the behaviour to the type that owns it.")
                                        .formatted(item.getName(), method.getName()))));
            }
        };
    }
}
