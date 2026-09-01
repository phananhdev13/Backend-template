package com.acme.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Connects to the Temporal server and starts this service's one worker, if it declares a task
 * queue. There is no first-party Spring Boot autoconfiguration for plain {@code temporal-sdk} to
 * build on the way {@code caching-support} builds on {@code spring-boot-starter-data-redis}'s -
 * {@code io.temporal:temporal-spring-boot-starter} exists but its own POM still imports Spring
 * Boot 2.7 at its latest release, so this module wires the SDK's own client classes directly. See
 * ADR-0016.
 */
@AutoConfiguration
@EnableConfigurationProperties(TemporalProperties.class)
public class TemporalSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.getTarget())
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(properties.getNamespace())
                .build();
        return WorkflowClient.newInstance(stubs, options);
    }

    @Bean
    TemporalWorkerLifecycle temporalWorkerLifecycle(
            WorkflowClient client, ApplicationContext context, TemporalProperties properties) {
        return new TemporalWorkerLifecycle(client, context, properties);
    }
}
