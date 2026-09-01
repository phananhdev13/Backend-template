package com.acme.grpc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.server.advice.GrpcAdvice;

/**
 * Wires the cross-cutting half of internal gRPC calls: exception translation and correlation
 * propagation. Every dependency here is optional, so a service that only exposes RPCs, a service
 * that only calls one, and a service that does both all start from the same library - the shape
 * {@code messaging-support} and {@code caching-support} already use for their own optional
 * backends.
 */
@AutoConfiguration
public class GrpcSupportAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GrpcAdvice.class)
    static class ServerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DomainExceptionGrpcAdvice domainExceptionGrpcAdvice() {
            return new DomainExceptionGrpcAdvice();
        }

        @Bean
        @ConditionalOnMissingBean
        CorrelationServerInterceptor correlationServerInterceptor() {
            return new CorrelationServerInterceptor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GlobalClientInterceptor.class)
    static class ClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CorrelationClientInterceptor correlationClientInterceptor() {
            return new CorrelationClientInterceptor();
        }
    }
}
