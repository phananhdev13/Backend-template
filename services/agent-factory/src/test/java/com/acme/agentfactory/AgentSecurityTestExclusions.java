package com.acme.agentfactory;

/**
 * The {@code spring.autoconfigure.exclude} value shared by every integration test here that is
 * not itself about security - registration, caching, the task queue, the OpenAPI contract.
 *
 * <p>Once this service depends on {@code security-support}, every endpoint requires a bearer
 * token by default (P-120's own {@code anyRequest().authenticated()}); excluding the security
 * autoconfiguration entirely - not merely configuring {@code permitAll()} - is what lets these
 * tests keep exercising their own concern with no live Keycloak. {@code OpaAutoConfiguration}
 * is deliberately NOT excluded: {@code OpaAgentAuthorizationAdapter} depends on its
 * {@code OpaAuthorization} bean unconditionally (Spring wires the whole context, not only what
 * one test calls), and {@code OpaClient} makes no network call until a request actually reaches
 * it, so leaving OPA's wiring in place costs a context-loading test nothing - confirmed the hard
 * way when excluding it too broke every context here with an unsatisfiable dependency.
 *
 * <p>Every other class below is Boot's own, confirmed necessary one at a time by running with
 * fewer and reading the resulting {@code NoSuchBeanDefinitionException}: Boot 4.1 gives a
 * servlet resource-server app no fewer than six separate autoconfiguration classes that each try
 * to build a default {@code SecurityFilterChain} (the plain HTTP Basic default, the
 * OAuth2-resource-server-specific one, and the actuator-management one, plus the
 * {@code UserDetailsService} and {@code HttpSecurity}-supplying machinery each of those needs) -
 * where Boot 3 had one. See {@code ActivateAgentVersionAuthorizationIntegrationTest} for where
 * the real chain is proven present and required instead.
 */
public final class AgentSecurityTestExclusions {

    public static final String PROPERTY = "spring.autoconfigure.exclude="
            + "com.acme.security.SecuritySupportAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.actuate.web.servlet"
            + ".ManagementWebSecurityAutoConfiguration,"
            + "org.springframework.boot.security.oauth2.server.resource.autoconfigure"
            + ".OAuth2ResourceServerAutoConfiguration,"
            + "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web"
            + ".OAuth2ResourceServerWebSecurityAutoConfiguration";

    private AgentSecurityTestExclusions() {}
}
