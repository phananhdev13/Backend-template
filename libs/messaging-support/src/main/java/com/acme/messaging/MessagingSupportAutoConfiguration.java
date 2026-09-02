package com.acme.messaging;

import com.acme.kernel.event.DomainEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the broker-facing half of messaging, and only the parts a service actually pulled in.
 *
 * <p>Every broker dependency in this module is optional, so the conditions here are what let a
 * service that publishes over Kafka, a service that consumes over AMQP, and a service that does
 * neither all start from the same library.
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ContractRegistry contractRegistry(MessagingProperties properties) {
        return new ContractRegistry(properties.getBasePackages());
    }

    /**
     * The typed publisher a use case injects instead of Spring's {@code ApplicationEventPublisher}.
     *
     * <p>Unconditional on any broker: a service that publishes domain events inside its own
     * process, with no Kafka or AMQP on the classpath at all, still needs this.
     */
    @Bean
    @ConditionalOnMissingBean
    DomainEventPublisher domainEventPublisher(ApplicationEventPublisher publisher) {
        return new ApplicationEventDomainEventPublisher(publisher);
    }

    /**
     * A UTC clock, present only if the service has not already declared its own.
     *
     * <p>{@link JdbcProcessedMessageStore} needs a {@code Clock} to timestamp and expire
     * idempotency records. Every service in this template already declares one for its domain code
     * to take time as a parameter, so this exists purely as a fallback: a service that pulls in
     * JDBC-backed idempotency without also wiring its own clock bean would otherwise fail to start
     * on an error that has nothing to say about messaging.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock messagingClock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    static class KafkaConfiguration {

        @Bean
        @ConditionalOnMissingBean(EventPublisher.class)
        EventPublisher kafkaEventPublisher(KafkaTemplate<String, Object> template, MessagingProperties properties) {
            return new KafkaEventPublisher(template, properties);
        }

        /**
         * Topics are provisioned from the contracts rather than declared by hand, so a stream's
         * configuration cannot drift away from what its consumers were promised.
         */
        @Bean
        @ConditionalOnProperty(prefix = "acme.messaging", name = "auto-provision", matchIfMissing = true)
        List<NewTopic> contractTopics(ContractRegistry registry, MessagingProperties properties) {
            return new KafkaTopicProvisioner(properties).toTopics(registry.all());
        }

        /**
         * Every producer in every service serialises the same way, decided once here.
         *
         * <p>Boot's autoconfigured {@code ProducerFactory} has no serializer until something sets
         * one - {@code ProducerConfig} has no default for {@code value.serializer}, and a producer
         * built without it fails at the first send, not at startup. {@link JacksonJsonSerializer} is
         * the Jackson-3 pair to {@link JacksonJsonDeserializer} below; using anything from
         * {@code com.fasterxml.jackson} here would silently produce bytes the Jackson-3 consumer
         * cannot read. Keys stay plain strings - the identifiers this platform partitions by are
         * always strings, and a key serializer that understood arbitrary types would invite a
         * second, undeclared way to shape a partition key.
         */
        @Bean
        @ConditionalOnMissingBean(DefaultKafkaProducerFactoryCustomizer.class)
        DefaultKafkaProducerFactoryCustomizer messagingProducerFactoryCustomizer() {
            return KafkaConfiguration::configureProducerSerializers;
        }

        // Boot hands back DefaultKafkaProducerFactory<?, ?>; each `?` is an independent capture, so
        // a concrete Serializer cannot be assigned to it directly. Every producer this platform
        // creates is <String, Object> - KafkaEventPublisher requires exactly that - so the cast here
        // states a fact the type system cannot express, not a real unknown.
        @SuppressWarnings("unchecked")
        private static void configureProducerSerializers(DefaultKafkaProducerFactory<?, ?> factory) {
            DefaultKafkaProducerFactory<String, Object> typed = (DefaultKafkaProducerFactory<String, Object>) factory;
            typed.setKeySerializer(new StringSerializer());
            typed.setValueSerializer(new JacksonJsonSerializer<>());
        }

        /**
         * The consumer-side pair of {@link #messagingProducerFactoryCustomizer()}.
         *
         * <p>Wrapped in {@link ErrorHandlingDeserializer} so a message that fails to deserialise -
         * wrong schema, a class the trusted-package list refuses, truncated bytes - is handed to the
         * listener container's error handler as a {@code DeserializationException} rather than
         * killing the consumer thread outright. Without that wrapper one poison message stops the
         * partition; with it, {@link #messagingErrorHandler} sends it to the dead-letter topic like
         * any other failure.
         */
        @Bean
        @ConditionalOnMissingBean(DefaultKafkaConsumerFactoryCustomizer.class)
        DefaultKafkaConsumerFactoryCustomizer messagingConsumerFactoryCustomizer(MessagingProperties properties) {
            return factory -> configureConsumerDeserializers(factory, properties);
        }

        // Same wildcard-capture reasoning as the producer side: every consumer factory this
        // platform builds is <String, Object>.
        @SuppressWarnings("unchecked")
        private static void configureConsumerDeserializers(
                DefaultKafkaConsumerFactory<?, ?> factory, MessagingProperties properties) {
            DefaultKafkaConsumerFactory<String, Object> typed = (DefaultKafkaConsumerFactory<String, Object>) factory;
            typed.setKeyDeserializer(new StringDeserializer());
            typed.setValueDeserializer(new ErrorHandlingDeserializer<>(trustedJsonDeserializer(properties)));
        }

        private static JacksonJsonDeserializer<Object> trustedJsonDeserializer(MessagingProperties properties) {
            JacksonJsonDeserializer<Object> deserializer = new JacksonJsonDeserializer<>();
            List<String> trusted = properties.getKafka().getTrustedPackages();
            if (trusted.isEmpty()) {
                trusted = properties.getBasePackages();
            }
            // The classes this service ever expects to receive are exactly the ones it scans for
            // @EventContract, so that list is a safe default with no configuration at all - and a
            // service that trusts nothing has declared it consumes nothing.
            if (!trusted.isEmpty()) {
                deserializer.addTrustedPackages(trusted.toArray(new String[0]));
            }
            return deserializer;
        }

        /**
         * Bounds redelivery and gives a poison message somewhere to go once it is exhausted.
         *
         * <p>Boot's {@code ConcurrentKafkaListenerContainerFactoryConfigurer} applies whichever
         * {@code CommonErrorHandler} bean is present to every {@code @KafkaListener} container, so
         * this is the one place retry and dead-lettering are decided for the whole service.
         */
        @Bean
        @ConditionalOnMissingBean(DefaultErrorHandler.class)
        DefaultErrorHandler messagingErrorHandler(
                KafkaTemplate<String, Object> template, MessagingProperties properties) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                    template, (record, exception) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
            int retries = Math.max(properties.getKafka().getMaxDeliveryAttempts() - 1, 0);
            FixedBackOff backOff = new FixedBackOff(properties.getKafka().getRetryBackoffMs(), retries);
            return new DefaultErrorHandler(recoverer, backOff);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcClient.class)
    static class IdempotencyConfiguration {

        @Bean
        @ConditionalOnMissingBean(ProcessedMessageStore.class)
        ProcessedMessageStore processedMessageStore(JdbcClient jdbcClient, Clock clock) {
            return new JdbcProcessedMessageStore(jdbcClient, clock);
        }
    }

    /**
     * Wires the RabbitMQ classic task queue: discovery, provisioning, publishing, retry and
     * dead-lettering. {@code rabbit-task-defaults.properties} turns retry on by default - a task
     * queue with retry off dead-letters on the first transient failure, which defeats the reason
     * a queue exists rather than an {@code @Async} method.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    @PropertySource("classpath:rabbit-task-defaults.properties")
    static class RabbitTaskConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TaskRegistry taskRegistry(MessagingProperties properties) {
            return new TaskRegistry(properties.getBasePackages());
        }

        /**
         * Every task serialises the same way, decided once here - the Jackson-3 converter, never
         * the Jackson-2-named {@code Jackson2JsonMessageConverter} still on the classpath for
         * back-compat. Boot's own {@code RabbitTemplateConfigurer} wires whichever single
         * {@code MessageConverter} bean is present into the autoconfigured {@code RabbitTemplate},
         * so declaring it here is enough - nothing here constructs a {@code RabbitTemplate} itself.
         */
        @Bean
        @ConditionalOnMissingBean(MessageConverter.class)
        MessageConverter taskMessageConverter() {
            return new JacksonJsonMessageConverter();
        }

        /**
         * The queues every discovered task needs, declared from the contracts rather than by hand.
         */
        @Bean
        @ConditionalOnProperty(prefix = "acme.messaging", name = "auto-provision", matchIfMissing = true)
        Declarables taskQueues(TaskRegistry registry) {
            return new RabbitTaskQueueProvisioner().toQueues(registry.all());
        }

        @Bean
        @ConditionalOnMissingBean(TaskPublisher.class)
        TaskPublisher rabbitTaskPublisher(RabbitTemplate template) {
            return new RabbitTaskPublisher(template);
        }

        /**
         * Where a task goes once every retry attempt has failed.
         *
         * <p>The routing key is computed from the failing message's own {@code receivedRoutingKey}
         * rather than fixed at construction, because one recoverer serves every queue this service
         * declares - the same reason {@link #messagingErrorHandler} above computes a Kafka record's
         * dead-letter topic from the record instead of taking one topic name. Publishing through the
         * default (nameless) exchange lands the message in the queue whose name matches that routing
         * key exactly, which {@link RabbitTaskQueueProvisioner} already declared as
         * {@code <queue>.dlq}.
         */
        @Bean
        @ConditionalOnMissingBean(MessageRecoverer.class)
        MessageRecoverer taskMessageRecoverer(RabbitTemplate template) {
            SpelExpressionParser parser = new SpelExpressionParser();
            Expression defaultExchange = parser.parseExpression("''");
            Expression deadLetterRoutingKey = parser.parseExpression("messageProperties.receivedRoutingKey + '.dlq'");
            return new RepublishMessageRecoverer(template, defaultExchange, deadLetterRoutingKey);
        }

        /**
         * The retry budget every task queue shares - bounded attempts and bounded backoff growth,
         * so a broker outage does not turn into an unbounded retry storm once it recovers.
         */
        @Bean
        RabbitListenerRetrySettingsCustomizer taskRetrySettingsCustomizer(MessagingProperties properties) {
            return settings -> configureRetry(settings, properties.getRabbit());
        }

        private static void configureRetry(RetryPolicySettings settings, MessagingProperties.Rabbit rabbit) {
            settings.setMaxRetries((long) Math.max(rabbit.getMaxDeliveryAttempts() - 1, 0));
            settings.setDelay(Duration.ofMillis(rabbit.getRetryInitialIntervalMs()));
            settings.setMultiplier(rabbit.getRetryMultiplier());
            settings.setMaxDelay(Duration.ofMillis(rabbit.getRetryMaxIntervalMs()));
        }
    }
}
