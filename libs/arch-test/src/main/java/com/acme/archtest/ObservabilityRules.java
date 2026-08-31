package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.UseCase;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * A request is followable end to end: P-060.
 *
 * <p>Most of this principle is about what is emitted, which no static rule can see. What
 * a rule can see is the two habits that quietly make logs unusable: a logger that is not
 * a constant, so its name varies with the instance, and a second logging facade, so half
 * the output misses the correlation identifier that the other half carries.
 */
public final class ObservabilityRules {

    @ArchTest
    public static final ArchRule loggersAreConstants = fields().that()
            .haveRawType("org.slf4j.Logger")
            .should()
            .beStatic()
            .andShould()
            .beFinal()
            .andShould()
            .bePrivate()
            .allowEmptyShould(true)
            .as("loggers are private static final (P-060)")
            .because("a non-constant logger is created per instance and named unpredictably, which "
                    + "breaks per-class log filtering. See docs/principles/P-060-observability.md");

    @ArchTest
    public static final ArchRule oneLoggingFacade = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.util.logging..", "org.apache.log4j..", "org.apache.commons.logging..")
            .allowEmptyShould(true)
            .as("SLF4J is the only logging facade (P-060)")
            .because("output through a second facade misses the MDC that carries the correlation "
                    + "identifier, and is invisible to the log pipeline's structure. "
                    + "See docs/principles/P-060-observability.md");

    @ArchTest
    public static final ArchRule useCasesEmitTheirIdentifier = classes()
            .that()
            .areAnnotatedWith(UseCase.class)
            .should(declareALogger())
            .allowEmptyShould(true)
            .as("every use case can say what it did (P-060)")
            .because("a use case that emits nothing leaves an incident with a request that entered "
                    + "the service and a database row that changed, and nothing in between. "
                    + "See docs/principles/P-060-observability.md");

    private ObservabilityRules() {}

    private static ArchCondition<JavaClass> declareALogger() {
        return new ArchCondition<>("declare an SLF4J logger") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean hasLogger = item.getFields().stream()
                        .anyMatch(field -> field.getRawType().getFullName().equals("org.slf4j.Logger"));
                if (hasLogger) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is a @UseCase with no logger. Emit one line naming the use case "
                                        + "identifier and the decision taken, so an incident can be "
                                        + "reconstructed from the logs alone.")
                                .formatted(item.getName())));
            }
        };
    }
}
