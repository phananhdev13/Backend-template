/**
 * Authentication at the edge, for Keycloak: a stateless OAuth2 resource server filter chain,
 * method security, and {@link com.acme.security.Actors#from(org.springframework.security.core.Authentication)} -
 * the one place a validated token becomes the framework-free
 * {@link com.acme.kernel.security.Actor} a use case actually reads.
 *
 * <p>{@code realm_access.roles} and {@code resource_access.<client>.roles} - Keycloak's own
 * shape for realm-wide and client-scoped roles - become Spring {@code GrantedAuthority} values
 * entirely through {@code spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions},
 * a real Boot 4.1 property (confirmed by decompiling {@code spring-boot-security-oauth2-resource-server}):
 * no hand-written {@code Converter<Jwt, Collection<GrantedAuthority>>} bean is needed, where
 * one always was on Boot 3.
 *
 * <p>See {@code docs/principles/P-120-security-at-use-case-boundary.md} and the {@code security}
 * skill.
 */
@NullMarked
package com.acme.security;

import org.jspecify.annotations.NullMarked;
