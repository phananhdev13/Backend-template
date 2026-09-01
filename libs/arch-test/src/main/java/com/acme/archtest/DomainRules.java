package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.DomainPolicy;
import com.acme.kernel.arch.OutputPort;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;

/**
 * The domain stays a domain: P-020.
 *
 * <p>Two habits erode it faster than anything else. A setter lets a caller move an aggregate into a
 * state its own rules forbid, which makes the rules advisory. A direct call to the system clock
 * makes behaviour depend on when the test happens to run, so the tests that would have caught the
 * first problem get deleted for being flaky.
 */
public final class DomainRules {

    private static final Set<String> CLOCK_OWNERS = Set.of(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.ZonedDateTime",
            "java.time.OffsetDateTime",
            "java.lang.System");

    private static final List<String> INFRASTRUCTURE = List.of(
            "org.springframework..",
            "jakarta..",
            "org.hibernate..",
            "tools.jackson..",
            "com.fasterxml.jackson..",
            "org.apache.kafka..",
            "com.rabbitmq..",
            "io.grpc..",
            "com.google.protobuf..",
            "software.amazon.awssdk..",
            "java.sql..",
            "javax.sql..");

    @ArchTest
    public static final ArchRule domainDependsOnlyOnDomain = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRASTRUCTURE.toArray(String[]::new))
            .allowEmptyShould(true)
            .as("the domain depends on no infrastructure (P-020)")
            .because("domain code that needs a container, a session or a serialiser to run can only be "
                    + "tested with one. See docs/principles/P-020-aggregate-consistency-boundaries.md");

    @ArchTest
    public static final ArchRule domainTypesDoNotExposeSetters = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should(ArchConditions.declareAPublicMethodNamed("set"))
            .allowEmptyShould(true)
            .as("domain types expose no setters (P-021)")
            .because("a setter is a way to reach a state the object's own rules forbid, which makes "
                    + "those rules advisory. Name the transition after what the business calls it. "
                    + "See docs/principles/P-021-illegal-states-unrepresentable.md");

    @ArchTest
    public static final ArchRule domainDoesNotReadTheSystemClock = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .callMethodWhere(ambientClockRead())
            .allowEmptyShould(true)
            .as("the domain takes time as a parameter (P-021)")
            .because("reading the clock directly makes 'an order placed yesterday' something a test "
                    + "cannot arrange, and makes behaviour depend on when the suite runs. Inject a "
                    + "java.time.Clock. See docs/principles/P-021-illegal-states-unrepresentable.md");

    @ArchTest
    public static final ArchRule policiesAreSideEffectFree = noClasses()
            .that()
            .areAnnotatedWith(DomainPolicy.class)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(OutputPort.class)
            .allowEmptyShould(true)
            .as("domain policies decide, they do not fetch (P-022)")
            .because("a policy that loads its own inputs cannot be evaluated in a unit test, and its "
                    + "answer depends on when it is asked. Pass the inputs in. "
                    + "See docs/principles/P-022-domain-services-and-policies.md");

    private DomainRules() {}

    private static DescribedPredicate<JavaMethodCall> ambientClockRead() {
        return new DescribedPredicate<>("a no-argument read of the system clock") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getTarget().getName();
                if (!CLOCK_OWNERS.contains(owner)) {
                    return false;
                }
                boolean noArguments = call.getTarget().getRawParameterTypes().isEmpty();
                return noArguments && (name.equals("now") || name.equals("currentTimeMillis"));
            }
        };
    }
}
