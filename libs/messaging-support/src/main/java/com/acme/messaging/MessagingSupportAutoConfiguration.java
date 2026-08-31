package com.acme.messaging;

import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

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
