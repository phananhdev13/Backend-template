package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * A state change and its announcement commit together: P-072.
 *
 * <p>A broker send between the save and the commit publishes an event for a change that a later
 * constraint violation rolls back - and no consumer can un-see it. A send after the commit loses
 * the event entirely if the process dies in between. The only way out is to record the intent to
 * publish in the same transaction, which is what {@code DomainEventPublisher} plus Spring
 * Modulith's publication registry does.
 */
public final class OutboxRules {

    /**
     * The types that actually send. Named individually rather than by package, which matters in
     * both directions: {@code org.springframework.kafka..} would also catch {@code @KafkaListener}
     * on a consumer, where receiving inside a transaction is the correct mark-then-act shape, and
     * naming only {@code com.acme.messaging..} missed a use case reaching for {@code KafkaTemplate}
     * directly. {@code StreamBridge} is deliberately absent: it is Spring Cloud Stream, which
     * ADR-0004 rules out, so a rule mentioning it would describe a dependency this repo cannot have.
     */
    private static final List<String> BROKER_SEND_TYPES = List.of(
            "org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.kafka.core.KafkaOperations",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.core.AmqpTemplate",
            "com.acme.messaging.EventPublisher",
            "com.acme.messaging.TaskPublisher");

    /**
     * Scoped to the whole application layer, not only {@code @UseCase}.
     *
     * <p>It keyed on the annotation alone, so a use case that delegated the send to a plain
     * collaborator beside it - the likeliest real shape - passed. The relay that genuinely talks to
     * the broker is an {@code @OutboundAdapter} in {@code ..adapter..} and is untouched by this:
     * it runs on {@code @ApplicationModuleListener}, after the commit, which is the whole point.
     */
    @ArchTest
    public static final ArchRule noBrokerCallInsideATransaction = noClasses()
            .that()
            .resideInAPackage("..application..")
            .or()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat(brokerSend())
            .allowEmptyShould(true)
            .as("use cases publish through the application context, never to a broker (P-072)")
            .because("a send inside the transaction announces a change that may roll back, and a send "
                    + "after it is lost if the process dies first. Publish with "
                    + "com.acme.kernel.event.DomainEventPublisher and let the outbox relay deliver. "
                    + "See docs/principles/P-072-transactional-outbox.md");

    private OutboxRules() {}

    private static DescribedPredicate<JavaClass> brokerSend() {
        return new DescribedPredicate<>("a client that sends to a broker") {
            @Override
            public boolean test(JavaClass type) {
                return BROKER_SEND_TYPES.contains(type.getFullName());
            }
        };
    }
}
