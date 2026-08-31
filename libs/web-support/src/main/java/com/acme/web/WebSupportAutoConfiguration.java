package com.acme.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Gives every service the same HTTP edge without any of them configuring it.
 *
 * <p>A service can still replace the advice by declaring its own bean, but the default is the
 * behaviour the API contract in {@code docs/principles/P-050-error-handling.md} describes.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DomainExceptionHandler domainExceptionHandler() {
        return new DomainExceptionHandler();
    }
}
