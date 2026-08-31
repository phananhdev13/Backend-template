package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.InboundAdapter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Authorisation happens at the use case: P-120.
 *
 * <p>A check in a controller protects one entry point. The same use case reached from a message
 * listener or a scheduled job is then unprotected, and nothing in the code says so.
 */
public final class SecurityRules {

    @ArchTest
    public static final ArchRule noAuthorisationInAdapters = noClasses()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.security.core.context.SecurityContextHolder")
            .allowEmptyShould(true)
            .as("inbound adapters do not make authorisation decisions (P-120)")
            .because("a check at one entry point leaves every other entry point to the same use case "
                    + "unprotected. See docs/principles/P-120-security-at-use-case-boundary.md");

    @ArchTest
    public static final ArchRule domainNeverReadsSecurityContext = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.security..")
            .allowEmptyShould(true)
            .as("the domain knows nothing about who is calling (P-120)")
            .because("a rule that reads the security context behaves differently in a batch job than "
                    + "in a request, and cannot be unit tested at all. Pass the caller in. "
                    + "See docs/principles/P-120-security-at-use-case-boundary.md");

    private SecurityRules() {}
}
