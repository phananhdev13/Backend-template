package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.InputPort;
import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.arch.UseCase;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * The use case is the unit of application logic: P-030.
 *
 * <p>These rules exist to stop the one drift that undoes the whole layout - the use case
 * quietly growing into a service object with a dozen unrelated methods, at which point
 * the transaction boundary, the authorisation boundary and the test boundary all stop
 * lining up with anything.
 */
public final class UseCaseRules {

    /** Referenced by name: arch-test does not depend on Spring. */
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    @ArchTest
    public static final ArchRule useCasesImplementExactlyOneInputPort = classes()
            .that()
            .areAnnotatedWith(UseCase.class)
            .should(implementExactlyOneInputPort())
            .allowEmptyShould(true)
            .as("a use case implements exactly one input port (P-030)")
            .because("one entry point per use case is what keeps it a use case. "
                    + "See docs/principles/P-030-use-case-unit-of-application-logic.md");

    @ArchTest
    public static final ArchRule inputPortsDeclareASingleOperation = classes()
            .that()
            .areAnnotatedWith(InputPort.class)
            .should(declareExactlyOnePublicMethod())
            .allowEmptyShould(true)
            .as("an input port declares a single operation (P-030)")
            .because("two operations on one port are two use cases sharing a name. "
                    + "See docs/principles/P-030-use-case-unit-of-application-logic.md");

    @ArchTest
    public static final ArchRule adaptersCallPortsNotImplementations = noClasses()
            .that()
            .resideInAPackage("..adapter..")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(UseCase.class)
            .allowEmptyShould(true)
            .as("adapters depend on input ports, never on use case implementations (P-031)")
            .because("depending on the implementation makes the port decorative and defeats substitution "
                    + "in tests. See docs/principles/P-031-dependencies-point-inwards.md");

    @ArchTest
    public static final ArchRule useCasesAreTheTransactionBoundary = classes()
            .that()
            .areAnnotatedWith(SPRING_TRANSACTIONAL)
            .should(carryAnApplicationRole())
            .allowEmptyShould(true)
            .as("transactions begin at the use case and nowhere else (P-030)")
            .because("a transaction opened in an adapter or a domain object makes the unit of "
                    + "consistency invisible from the code that decides what belongs in it, and two "
                    + "such boundaries nest into one nobody designed. "
                    + "See docs/principles/P-030-use-case-unit-of-application-logic.md");

    private static ArchCondition<JavaClass> carryAnApplicationRole() {
        return new ArchCondition<>("be a @UseCase or a @ReadModel") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, UseCase.class) || Annotations.has(item, ReadModel.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is @Transactional but is neither a @UseCase nor a @ReadModel. Move the "
                                        + "boundary up to the use case that owns the operation.")
                                .formatted(item.getName())));
            }
        };
    }

    private UseCaseRules() {}

    private static ArchCondition<JavaClass> implementExactlyOneInputPort() {
        return new ArchCondition<>("implement exactly one @InputPort interface") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                List<JavaClass> ports = item.getAllRawInterfaces().stream()
                        .filter(candidate -> Annotations.has(candidate, InputPort.class))
                        .toList();
                if (ports.size() == 1) {
                    return;
                }
                String detail = ports.isEmpty()
                        ? "implements none: declare the port it satisfies and annotate it @InputPort"
                        : "implements " + ports.size() + ": "
                                + ports.stream().map(JavaClass::getSimpleName).toList()
                                + ". Split it into one class per use case";
                events.add(SimpleConditionEvent.violated(
                        item, "%s is annotated @UseCase but %s.".formatted(item.getName(), detail)));
            }
        };
    }

    private static ArchCondition<JavaClass> declareExactlyOnePublicMethod() {
        return new ArchCondition<>("declare exactly one public method") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                long methods = item.getMethods().stream()
                        .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                        .filter(method -> !method.getName().contains("$"))
                        .count();
                if (methods == 1) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s is an @InputPort with %d operations. One port, one use case."
                                .formatted(item.getName(), methods)));
            }
        };
    }
}
