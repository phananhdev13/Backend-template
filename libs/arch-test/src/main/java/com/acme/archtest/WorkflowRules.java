package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.workflow.WorkflowDefinition;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * A workflow definition stays deterministic and replay-safe: P-033.
 *
 * <p>Temporal reconstructs a workflow's state by replaying its history against the same
 * code, not by loading it from storage. Anything that could answer differently on replay
 * than it did the first time - a Spring-injected dependency resolved at a different time,
 * an activity timeout each call site invents for itself - turns a replay into a
 * {@code NonDeterministicException} an operator discovers in production, not a test.
 */
public final class WorkflowRules {

    /**
     * The same shape {@code DomainRules.INFRASTRUCTURE} forbids in the domain, minus
     * {@code io.temporal..} itself - the one framework dependency
     * {@link WorkflowDefinition} exists to allow, for the timer and activity-stub APIs a
     * workflow has no other way to reach.
     */
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
            "java.sql..",
            "javax.sql..");

    @ArchTest
    public static final ArchRule workflowDefinitionsStayFrameworkFree = noClasses()
            .that()
            .areAnnotatedWith(WorkflowDefinition.class)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRASTRUCTURE.toArray(String[]::new))
            .allowEmptyShould(true)
            .as("workflow definitions depend on no framework but Temporal's own (P-033)")
            .because("Temporal reconstructs a workflow by replaying its history through the same "
                    + "code; a dependency that could answer differently between the original run and "
                    + "a replay - a Spring bean, a database connection - is what turns a replay into a "
                    + "NonDeterministicException. See docs/principles/P-033-workflow-definitions-are-deterministic.md");

    @ArchTest
    public static final ArchRule workflowsBuildActivityOptionsThroughTheSanctionedFactory = noClasses()
            .that()
            .areAnnotatedWith(WorkflowDefinition.class)
            .should()
            .callMethodWhere(activityOptionsBuilderCall())
            .allowEmptyShould(true)
            .as("activity options are built through TemporalActivityOptions, not by hand (P-033)")
            .because("io.temporal.activity.ActivityOptions builds cleanly with no timeout at all, "
                    + "which defaults to unlimited - a workflow blocked on an activity that never times "
                    + "out is the RPC-call-with-no-deadline mistake P-051 already refuses, one layer "
                    + "deeper. TemporalActivityOptions is the one place a timeout is mandatory. "
                    + "See docs/principles/P-033-workflow-definitions-are-deterministic.md");

    private WorkflowRules() {}

    private static DescribedPredicate<JavaMethodCall> activityOptionsBuilderCall() {
        return new DescribedPredicate<>("a call to ActivityOptions.newBuilder()") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().getFullName().equals("io.temporal.activity.ActivityOptions")
                        && call.getTarget().getName().equals("newBuilder");
            }
        };
    }
}
