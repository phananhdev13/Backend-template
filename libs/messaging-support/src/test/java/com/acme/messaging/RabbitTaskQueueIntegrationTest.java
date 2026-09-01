package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.acme.messaging.fixture.FailingTask;
import com.acme.messaging.fixture.SampleTask;
import com.acme.messaging.fixture.TaskListeners;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the RabbitMQ task queue actually delivers and actually dead-letters, against a real
 * broker - not merely that the context starts. A context-loading test alone would not catch a
 * routing key that does not match the queue it was meant to reach, or a retry policy that never
 * takes effect; those only show up when a message is really sent and really redelivered.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitTaskQueueIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        // spring-kafka is on this module's own classpath even for a test that never
                        // touches Kafka (see the pom's optional-dependency comment), so
                        // KafkaConfiguration's @ConditionalOnClass always evaluates true here - this
                        // needs Boot's own Kafka autoconfiguration alongside Rabbit's, the same way a
                        // real service with both starters on the classpath would.
                        org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
                        RabbitAutoConfiguration.class,
                        MessagingSupportAutoConfiguration.class))
                .withBean(TaskListeners.class, TaskListeners::new)
                // A stand-in for whatever DataSource the owning service configures - this module's
                // idempotency configuration activates whenever JdbcClient is on the classpath,
                // which it always is here, regardless of what this particular test is exercising.
                .withBean(DataSource.class, SimpleDriverDataSource::new)
                .withBean(JdbcClient.class, () -> JdbcClient.create(new SimpleDriverDataSource()))
                .withPropertyValues(
                        "acme.messaging.base-packages=com.acme.messaging.fixture",
                        // Never dialled for this test - Kafka's ProducerFactory connects lazily.
                        "spring.kafka.bootstrap-servers=localhost:59092",
                        // A small, fast retry budget - the second test waits out the whole budget,
                        // and a production-shaped one would make the suite slow for no more coverage.
                        "acme.messaging.rabbit.max-delivery-attempts=2",
                        "acme.messaging.rabbit.retry-initial-interval-ms=50",
                        "acme.messaging.rabbit.retry-multiplier=1.0",
                        "acme.messaging.rabbit.retry-max-interval-ms=50",
                        "spring.rabbitmq.host=" + RABBIT.getHost(),
                        "spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                        "spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                        "spring.rabbitmq.password=" + RABBIT.getAdminPassword());
    }

    @Test
    void aSubmittedTaskIsReceivedByARealConsumerThroughARealBroker() {
        contextRunner().run(context -> {
            TaskPublisher publisher = context.getBean(TaskPublisher.class);
            TaskListeners listeners = context.getBean(TaskListeners.class);

            publisher.submit(new SampleTask("job-1", Instant.now()));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(listeners.received())
                            .extracting(SampleTask::jobId)
                            .contains("job-1"));
        });
    }

    @Test
    void aTaskThatAlwaysFailsIsDeadLetteredOnceItsRetryBudgetIsExhausted() {
        contextRunner().run(context -> {
            TaskPublisher publisher = context.getBean(TaskPublisher.class);
            TaskListeners listeners = context.getBean(TaskListeners.class);
            RabbitTemplate template = context.getBean(RabbitTemplate.class);

            publisher.submit(new FailingTask("job-2", Instant.now()));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(listeners.failingAttempts())
                            .as("first try plus one retry")
                            .isEqualTo(2));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Message deadLettered = template.receive("test.failing-task.dlq", 200);
                assertThat(deadLettered)
                        .as("the exhausted task landed on its dead-letter queue")
                        .isNotNull();
            });
        });
    }
}
