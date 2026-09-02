package com.acme.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Names the generated OpenAPI document after the service that generated it.
 *
 * <p>Without this bean, springdoc's default {@code info} block reads {@code "title": "OpenAPI
 * definition", "version": "v0"} for every service in this repository - identical, uninformative,
 * and useless as a way to tell two services' contracts apart once both are checked into
 * {@code contracts/api/}. A service that needs a real release number in {@code info.version}
 * (wired to its build, not a placeholder) replaces this bean with its own.
 *
 * <p>{@code @ConditionalOnClass} keeps this inert for a service that has not added springdoc -
 * {@code io.swagger.v3.oas.models.OpenAPI} is on the classpath only once the real starter is.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiInfoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenAPI apiInfo(@Value("${spring.application.name:service}") String serviceName) {
        return new OpenAPI()
                .info(new Info()
                        .title(serviceName)
                        .version("1.0")
                        .description("Generated from this service's controllers - never hand-edited. "
                                + "See docs/principles/P-080-api-versioning.md"));
    }
}
