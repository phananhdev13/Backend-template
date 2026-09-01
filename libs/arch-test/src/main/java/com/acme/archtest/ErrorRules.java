package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.error.DomainException;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Failures carry domain meaning, not transport meaning: P-050.
 *
 * <p>The moment a use case throws something that knows about HTTP, it has stopped being
 * reachable from anywhere else. The same use case invoked from a Kafka listener now
 * produces a 404 nobody will ever see, and the listener has no way to tell a missing
 * record from a broken dependency.
 */
public final class ErrorRules {

    private static final String[] TRANSPORT_PACKAGES = {
        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..", "io.grpc..",
    };

    @ArchTest
    public static final ArchRule domainNeverThrowsWebExceptions = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(TRANSPORT_PACKAGES)
            .allowEmptyShould(true)
            .as("domain and application code knows nothing about HTTP (P-050)")
            .because("a status code chosen three layers in cannot be right for every caller. "
                    + "See docs/principles/P-050-error-handling.md");

    @ArchTest
    public static final ArchRule businessFailuresExtendDomainException = classes()
            .that()
            .areAssignableTo(Throwable.class)
            .and()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .beAssignableTo(DomainException.class)
            .allowEmptyShould(true)
            .as("failures raised by the domain are DomainExceptions (P-050)")
            .because("the edge maps a failure to a response using its kind and code; an exception "
                    + "outside that hierarchy can only become a 500. "
                    + "See docs/principles/P-050-error-handling.md");

    private ErrorRules() {}
}
