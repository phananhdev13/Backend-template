package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.event.EventHandler;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * Authorisation happens at the use case: P-120.
 *
 * <p>A check in a controller protects one entry point. The same use case reached from a message
 * listener or a scheduled job is then unprotected, and nothing in the code says so.
 */
public final class SecurityRules {

    /**
     * The annotations that decide, as opposed to the ones that describe. Spring Security's own,
     * plus the Jakarta/JSR-250 spelling, because a project that has {@code @RolesAllowed} enabled
     * gets exactly the same one-entry-point behaviour from it.
     */
    private static final List<String> AUTHORISATION_ANNOTATIONS = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.prepost.PreFilter",
            "org.springframework.security.access.prepost.PostFilter",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.DenyAll");

    /**
     * Reading who is calling out of ambient state, rather than being handed it. Named types rather
     * than the whole {@code org.springframework.security} tree on purpose: a {@code @UseCase} may
     * legitimately carry {@code @PreAuthorize} for the coarse, declarative check P-120 asks for,
     * and banning the package would ban that too.
     */
    private static final List<String> AMBIENT_IDENTITY_TYPES = List.of(
            "org.springframework.security.core.context.SecurityContextHolder",
            "org.springframework.security.core.Authentication",
            "org.springframework.security.oauth2.jwt.Jwt",
            "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken");

    @ArchTest
    public static final ArchRule noAuthorisationInAdapters = classes()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .or()
            .areAnnotatedWith(EventHandler.class)
            .should(takeNoAuthorisationDecision())
            .allowEmptyShould(true)
            .as("inbound adapters do not make authorisation decisions (P-120)")
            .because("a check at one entry point leaves every other entry point to the same use case "
                    + "unprotected. See docs/principles/P-120-security-at-use-case-boundary.md");

    @ArchTest
    public static final ArchRule domainNeverReadsSecurityContext = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .or()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat(ambientIdentity())
            .allowEmptyShould(true)
            .as("the domain and the use case are handed the caller, never look it up (P-120)")
            .because("a rule that reads the security context behaves differently in a batch job than "
                    + "in a request, and cannot be unit tested at all. Pass a kernel Actor in. "
                    + "See docs/principles/P-120-security-at-use-case-boundary.md");

    private SecurityRules() {}

    private static DescribedPredicate<JavaClass> ambientIdentity() {
        return new DescribedPredicate<>("a type that reads the caller out of ambient state") {
            @Override
            public boolean test(JavaClass type) {
                return AMBIENT_IDENTITY_TYPES.contains(type.getFullName());
            }
        };
    }

    private static ArchCondition<JavaClass> takeNoAuthorisationDecision() {
        return new ArchCondition<>("carry no authorisation annotation") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                report(
                        item,
                        item.getAnnotations().stream()
                                .map(annotation -> annotation.getRawType().getFullName())
                                .filter(AUTHORISATION_ANNOTATIONS::contains)
                                .toList(),
                        "",
                        events);
                for (JavaMethod method : item.getMethods()) {
                    report(
                            item,
                            method.getAnnotations().stream()
                                    .map(annotation -> annotation.getRawType().getFullName())
                                    .filter(AUTHORISATION_ANNOTATIONS::contains)
                                    .toList(),
                            "#" + method.getName(),
                            events);
                }
            }

            private void report(JavaClass item, List<String> found, String where, ConditionEvents events) {
                for (String annotation : found) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s%s is annotated @%s. An adapter translates and delegates; it does not "
                                            + "decide. Move the check to the @UseCase or @ReadModel this "
                                            + "adapter calls, so every other transport reaching the same "
                                            + "operation inherits it.")
                                    .formatted(
                                            item.getName(),
                                            where,
                                            annotation.substring(annotation.lastIndexOf('.') + 1))));
                }
            }
        };
    }
}
