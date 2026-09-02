package com.acme.security;

import com.acme.kernel.security.Actor;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The one place a validated {@link Jwt} becomes the framework-free {@link Actor} a use case
 * actually reads - the translation {@code Actors.from(jwt)} in P-120's own worked example
 * names, made real.
 *
 * <p>Reads authorities from the already-authenticated {@link JwtAuthenticationToken} rather
 * than re-deriving them from the {@link Jwt}'s raw claims a second time - Spring Security has
 * already run {@code spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions}
 * once, against {@code realm_access.roles} and whichever {@code resource_access.<client>.roles}
 * this service configured, and that resolved list is the single source of truth for what this
 * caller may do. Re-parsing the {@code Jwt} here would be a second, divergent way to answer the
 * same question.
 *
 * <p>{@code "ROLE_"} is stripped from each authority so a domain rule reads {@code
 * actor.hasRole("agent-admin")}, never {@code actor.hasRole("ROLE_agent-admin")} - the prefix is
 * Spring's own {@code hasRole(...)} SpEL convention, not part of what Keycloak calls the role,
 * and not something a use case should have to know about.
 */
public final class Actors {

    private static final String ROLE_PREFIX = "ROLE_";

    private Actors() {}

    /**
     * @throws IllegalStateException if {@code authentication} is not a {@link JwtAuthenticationToken} -
     *     which would mean this service authenticates some other way than the OAuth2 resource
     *     server chain {@code SecuritySupportAutoConfiguration} wires, a configuration error
     *     worth failing loudly on rather than returning a nonsense {@link Actor}.
     */
    public static Actor from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException(
                    "Expected a JwtAuthenticationToken, got %s - is the OAuth2 resource server chain wired?"
                            .formatted(authentication.getClass().getName()));
        }
        Jwt jwt = token.getToken();
        Set<String> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(Actors::withoutRolePrefix)
                .collect(Collectors.toUnmodifiableSet());
        String username = jwt.getClaimAsString("preferred_username");
        return new Actor(token.getName(), username != null ? username : token.getName(), roles);
    }

    private static String withoutRolePrefix(String authority) {
        return authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority;
    }
}
