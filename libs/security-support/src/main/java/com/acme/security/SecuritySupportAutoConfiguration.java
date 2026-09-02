package com.acme.security;

import com.acme.web.ProblemTypes;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stateless OAuth2 resource server filter chain every service behind this platform's
 * Keycloak realm shares, and the one place a Spring Security failure is translated into the
 * same RFC 9457 shape {@code web-support}'s {@code DomainExceptionHandler} already gives every
 * other failure.
 *
 * <p>Two different failure paths need two different translation points, and both exist because
 * {@code @RestControllerAdvice} only sees exceptions the {@code DispatcherServlet} dispatches
 * to a controller:
 *
 * <ul>
 *   <li>A missing or invalid bearer token fails inside this filter chain, before the request
 *       ever reaches a controller - {@link #authenticationEntryPoint()} below is what renders
 *       that as a problem response instead of Spring Security's own default body.
 *   <li>An {@code @PreAuthorize} denial on a {@code @UseCase} fails as a normal exception
 *       thrown from inside the controller's call stack - {@code DomainExceptionHandler}'s own
 *       {@code AccessDeniedException} handler in {@code web-support} covers that one; nothing
 *       new is needed here for it.
 * </ul>
 *
 * <p>See docs/principles/P-120-security-at-use-case-boundary.md and the {@code security} skill
 * for how a Keycloak realm's {@code realm_access.roles} and {@code resource_access.<client>.roles}
 * claims become Spring {@code GrantedAuthority} values - entirely through
 * {@code spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions}, a Boot 4.1
 * property, not a hand-written {@code Converter} bean.
 *
 * <p>Authorisation against OPA is a separate concern, wired independently by
 * {@link com.acme.security.opa.OpaAutoConfiguration} - a service authenticates with Keycloak,
 * authorises with OPA, or both, and this class only ever does the first.
 */
@AutoConfiguration
@EnableWebSecurity
@EnableMethodSecurity
public class SecuritySupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AccessDeniedProblemAdvice accessDeniedProblemAdvice() {
        return new AccessDeniedProblemAdvice();
    }

    /**
     * {@code jwtAuthenticationConverter} is autowired explicitly, not left to
     * {@code Customizer.withDefaults()} - confirmed the hard way, against a real Keycloak
     * token, that {@code .jwt(Customizer.withDefaults())} alone does not pick up Boot's own
     * {@code JwtConverterConfiguration}-built converter bean here, and every authority is lost:
     * the caller authenticates but resolves to no roles at all rather than failing loudly.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // A bearer token carries its own proof of authenticity; a CSRF token defends
                // against a browser silently attaching a cookie the server trusted. Nothing
                // here is cookie-authenticated, so there is nothing for CSRF to defend.
                .csrf(csrf -> csrf.disable())
                // No HttpSession is ever created - the Jwt is revalidated on every request, and
                // a session would be one more place an identity could go stale or leak across
                // pooled threads, the same hazard CorrelationIdFilter's own Javadoc names for MDC.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Every other URL only needs to be authenticated - which record a caller
                        // may act on is a @PreAuthorize question at the use case, per P-120, not
                        // a URL-pattern question here.
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    /**
     * Renders a missing, malformed or expired bearer token as the same problem shape every
     * other failure in this service produces, instead of Spring Security's own default body -
     * which carries no correlation id and does not match {@code ErrorKindStatus}'s mapping.
     *
     * <p>Not a {@link HttpStatusEntryPoint} alone: that writes only a bare status, and this
     * platform's contract is that every 4xx and 5xx carries a {@code type}, a {@code detail}
     * and (where one exists yet - {@code CorrelationIdFilter} runs before this point, so it
     * does) a {@code correlationId}.
     */
    @Bean
    public org.springframework.security.web.AuthenticationEntryPoint authenticationEntryPoint() {
        JsonMapper mapper = JsonMapper.builder().build();
        return (request, response, exception) -> {
            ProblemDetail problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
            problem.setType(ProblemTypes.of("auth.unauthenticated"));
            problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
            problem.setInstance(URI.create(request.getRequestURI()));
            writeProblem(response, HttpStatus.UNAUTHORIZED, problem, mapper);
        };
    }

    /**
     * Renders a filter-chain-level access denial the same way - reachable only for a rule
     * expressed in {@code authorizeHttpRequests} above, since every {@code @PreAuthorize}
     * denial instead reaches {@code DomainExceptionHandler} in {@code web-support}.
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        JsonMapper mapper = JsonMapper.builder().build();
        return (request, response, exception) -> {
            ProblemDetail problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The caller may not do this");
            problem.setType(ProblemTypes.of("auth.forbidden"));
            problem.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
            problem.setInstance(URI.create(request.getRequestURI()));
            writeProblem(response, HttpStatus.FORBIDDEN, problem, mapper);
        };
    }

    private static void writeProblem(
            HttpServletResponse response, HttpStatus status, ProblemDetail problem, JsonMapper mapper)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
