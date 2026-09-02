package com.acme.kernel.security;

import java.util.Set;

/**
 * Who is calling, carried as data rather than read from ambient context.
 *
 * <p>{@code SecurityContextHolder} is a thread-local, which makes a domain rule that reads it
 * behave differently in a batch job than in a request, and impossible to unit test without
 * standing up a security context first. Passing an {@code Actor} as a command field instead
 * means the same rule runs identically from a controller, a message listener or a test - see
 * {@code SecurityRules.domainNeverReadsSecurityContext} in {@code libs/arch-test}.
 *
 * <p>{@code subject} is the identity token issuers agree on - the JWT {@code sub} claim, stable
 * for the lifetime of the account even if {@code username} changes. Authorisation code should
 * compare {@code subject} against a resource's owner id, never {@code username} - a display
 * name is not an identifier.
 *
 * <p>Carries no tenant field: this platform's identity provider (Keycloak) has no standard
 * tenant claim - multi-tenancy is either realm-per-tenant (the realm is already implicit in
 * which issuer validated the token) or a custom protocol mapper a deployment adds itself. A
 * service that needs one reads it the same way it reads {@code roles} below: from the adapter
 * that builds this {@code Actor}, not from a field every caller of this record must populate
 * with something meaningless when there is no tenant.
 */
public record Actor(String subject, String username, Set<String> roles) {

    public Actor {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
