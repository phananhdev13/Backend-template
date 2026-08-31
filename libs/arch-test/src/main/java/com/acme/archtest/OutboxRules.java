package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.UseCase;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A state change and its announcement commit together: P-072.
 *
 * <p>A broker send between the save and the commit publishes an event for a change that a later
 * constraint violation rolls back - and no consumer can un-see it. A send after the commit loses
 * the event entirely if the process dies in between. The only way out is to record the intent to
 * publish in the same transaction, which is what {@code ApplicationEventPublisher} plus Spring
 * Modulith's publication registry does.
 */
public final class OutboxRules {

    @ArchTest
    public static final ArchRule noBrokerCallInsideATransaction = noClasses()
            .that()
            .areAnnotatedWith(UseCase.class)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.acme.messaging..", "org.springframework.kafka..", "org.springframework.amqp..")
            .allowEmptyShould(true)
            .as("use cases publish through the application context, never to a broker (P-072)")
            .because("a send inside the transaction announces a change that may roll back, and a send "
                    + "after it is lost if the process dies first. Publish with "
                    + "ApplicationEventPublisher and let the outbox deliver. "
                    + "See docs/principles/P-072-transactional-outbox.md");

    private OutboxRules() {}
}
