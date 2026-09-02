package com.acme.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires ShedLock's JDBC lock so a plain {@code @Scheduled} method is safe to run on more than
 * one instance at once.
 *
 * <p>ShedLock is deliberately not the same mechanism as Quartz below. It adds one thing - "at
 * most one instance runs this tick" - to a method that is otherwise ordinary Spring
 * {@code @Scheduled}, at the cost of one small lock table
 * ({@code db/migration/scheduling-shedlock/V002__shedlock.sql}, reserving version 2 in a
 * consuming service's combined Flyway sequence) and a best-effort guarantee: a node
 * holding the lock that dies mid-execution releases it only when {@code lockAtMostFor} elapses,
 * not instantly. That trade is exactly right for an idempotent maintenance job and wrong for
 * anything that must never silently double-run mid-crash - reach for Quartz's clustered
 * JDBC JobStore instead, which recovers a dead node's fired triggers explicitly rather than
 * waiting out a timeout. See docs/principles/P-132-scheduled-jobs.md.
 *
 * <p>{@code defaultLockAtMostFor} is a safety ceiling, not a target: it is how long the lock
 * survives a node dying mid-job, so every {@code @SchedulerLock} that runs longer than a few
 * minutes should override it per-method rather than rely on this default.
 *
 * <p>{@code @EnableScheduling} is turned on here too - a service pulling in ShedLock has no
 * reason to also remember the annotation that makes {@code @Scheduled} itself work.
 *
 * <p>{@code @AutoConfigureAfter(DataSourceAutoConfiguration)}, referenced by name so this module
 * does not need a compile dependency on {@code spring-boot-jdbc} just to name it, is load-bearing
 * - without it, {@code @ConditionalOnBean(DataSource.class)} can be evaluated before Boot's own
 * {@code DataSource} bean definition exists, so the condition fails and this bean is silently
 * never registered. Confirmed the hard way, by omitting it first: the exact class of ordering
 * bug ADR-0019's observability work also ran into wiring {@code @Observed} - see
 * {@code ObservedMetricProbeTest} in order-service for that one's own regression test.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(LockProvider.class)
@ConditionalOnBean(DataSource.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
