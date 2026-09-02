package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.Adr;
import com.acme.kernel.arch.InboundAdapter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * A scheduled job is either exclusive across instances by construction, or wrong: P-132.
 *
 * <p>Every service this template ships runs more than one instance in production. A plain
 * {@code @Scheduled} method has no idea another instance exists - it fires on every one of
 * them, on every tick, which is silent double-processing until an incident makes it visible.
 * {@code @InboundAdapter(AdapterKind.SCHEDULER)} is this repo's existing marker for "a clock
 * tick drives a use case" (see P-072's outbox relay); what this rule adds is the check that
 * something actually stops that adapter from running twice at once.
 */
public final class SchedulingRules {

    /** Referenced by name: arch-test does not depend on ShedLock or Quartz. */
    private static final String SHEDLOCK_SCHEDULER_LOCK = "net.javacrumbs.shedlock.spring.annotation.SchedulerLock";

    private static final String QUARTZ_JOB = "org.quartz.Job";

    @ArchTest
    public static final ArchRule schedulerAdaptersAreClusterSafe = classes()
            .that()
            .areAnnotatedWith(InboundAdapter.class)
            .should(beProvablySafeAcrossInstances())
            .allowEmptyShould(true)
            .as("a scheduled job is safe to run on more than one instance at once (P-132)")
            .because("a @Scheduled method with no cross-instance exclusivity mechanism runs once per "
                    + "instance, not once per tick - every instance does the job, every time. "
                    + "See docs/principles/P-132-scheduled-jobs.md");

    private SchedulingRules() {}

    private static ArchCondition<JavaClass> beProvablySafeAcrossInstances() {
        return new ArchCondition<>("carry a cross-instance exclusivity mechanism, or an @Adr") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean isSchedulerAdapter = Annotations.find(item, InboundAdapter.class)
                        .map(a -> Annotations.enumName(a, "value", "").equals(AdapterKind.SCHEDULER.name()))
                        .orElse(false);
                if (!isSchedulerAdapter || Annotations.has(item, Adr.class)) {
                    return;
                }
                boolean shedLocked = item.getMethods().stream()
                        .anyMatch(method -> Annotations.hasNamed(method, SHEDLOCK_SCHEDULER_LOCK));
                boolean quartzJob = item.getAllRawInterfaces().stream()
                        .anyMatch(type -> type.getFullName().equals(QUARTZ_JOB));
                if (shedLocked || quartzJob) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is @InboundAdapter(SCHEDULER) with no cross-instance exclusivity: no "
                                        + "@SchedulerLock method (ShedLock) and it does not implement "
                                        + "org.quartz.Job (Quartz's clustered JobStore). Add one, or suppress "
                                        + "with @Adr if this job is safe under concurrent execution some other "
                                        + "way - an idempotent claim with SELECT ... FOR UPDATE SKIP LOCKED, "
                                        + "for example.")
                                .formatted(item.getName())));
            }
        };
    }
}
