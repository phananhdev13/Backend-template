package com.acme.security.opa;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Wires {@link OpaClient} for every service on this platform under the {@code opa}
 * service-client group, purely by setting {@code spring.http.serviceclient.opa.base-url} to
 * that service's OPA sidecar decision endpoint - the same shape as any other outbound HTTP
 * dependency per the resilience skill.
 *
 * <p>Deliberately separate from {@link com.acme.security.SecuritySupportAutoConfiguration}:
 * that class needs a working {@code JwtAuthenticationConverter}, which needs a real issuer
 * configured, so a test that only wants to exercise OPA would otherwise be forced to also
 * stand up Keycloak. Authentication and authorisation are independent concerns here, and a
 * service (or a test) can depend on either without the other.
 */
@AutoConfiguration
@ImportHttpServices(group = "opa", types = OpaClient.class)
public class OpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpaAuthorization opaAuthorization(OpaClient opaClient) {
        return new OpaAuthorization(opaClient);
    }
}
