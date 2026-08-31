package com.acme.messaging;

import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
