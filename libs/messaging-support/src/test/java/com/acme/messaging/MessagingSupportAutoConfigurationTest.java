package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Proves the application context actually starts with a Kafka producer and consumer ready to
 * serialise, without a broker.
 *
 * <p>This is the regression test for a bug that unit tests on individual classes cannot catch:
 * {@code KafkaEventPublisher} compiled and its own logic tested cleanly while the application it
 * ships in would have failed to start, because nothing supplied a {@link KafkaTemplate} bean or a
 * value serializer. Only a context-loading test - Boot actually assembling the beans - would have
 * caught it, which is why one exists here rather than only unit tests of the pieces.
 */
class MessagingSupportAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
                    // amqp-client is on this module's own classpath even for a service that never
                    // adds spring-boot-starter-amqp itself (see the pom's optional-dependency
                    // comment), so RabbitTaskConfiguration's @ConditionalOnClass always evaluates
                    // true here - this needs Boot's own Rabbit autoconfiguration alongside Kafka's,
                    // the same way a real service with both starters on the classpath would.
                    RabbitAutoConfiguration.class,
                    MessagingSupportAutoConfiguration.class))
            // A stand-in for whatever DataSource the owning service configures. Constructing a
            // SimpleDriverDataSource never opens a connection, so this needs no real database - it
            // only has to exist, the same way it would in any service that pulls in spring-jdbc.
            .withBean(DataSource.class, SimpleDriverDataSource::new)
            .withBean(JdbcClient.class, () -> JdbcClient.create(new SimpleDriverDataSource()))
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    // Never dialled for a context-loading test - Boot's ConnectionFactory connects
                    // lazily on first use, the same reason the Kafka bootstrap server above is fake.
                    "spring.rabbitmq.host=localhost",
                    "spring.rabbitmq.port=59672",
                    "acme.messaging.base-packages=com.acme.messaging");

    @Test
    void theContextStartsWithAKafkaTemplateReadyToPublish() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KafkaTemplate.class);
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context.getBean(EventPublisher.class)).isInstanceOf(KafkaEventPublisher.class);
        });
    }

    @Test
    void theProducerFactoryIsCustomizedWithTheJackson3Serializer() {
        contextRunner.run(context -> {
            DefaultKafkaProducerFactory<?, ?> factory =
                    (DefaultKafkaProducerFactory<?, ?>) context.getBean("kafkaProducerFactory");

            assertThat(factory.getValueSerializer()).isInstanceOf(JacksonJsonSerializer.class);
        });
    }

    @Test
    void theConsumerFactoryWrapsDeserializationFailuresRatherThanKillingTheContainer() {
        contextRunner.run(context -> {
            DefaultKafkaConsumerFactory<?, ?> factory =
                    (DefaultKafkaConsumerFactory<?, ?>) context.getBean("kafkaConsumerFactory");

            assertThat(factory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        });
    }

    @Test
    void aDeadLetterErrorHandlerIsRegisteredForEveryListenerContainer() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(DefaultErrorHandler.class));
    }
}
