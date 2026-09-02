package com.acme.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the clustered JDBC {@code JobStore} defaults actually produce a working scheduler
 * against a real Postgres - a job registered as a bean is picked up by Boot's own
 * {@code QuartzAutoConfiguration} and fires - not merely that the properties are syntactically
 * valid.
 *
 * <p>{@code spring.quartz.properties.org.quartz.jobStore.isClustered=true} is exactly the
 * setting this test cannot prove by itself: proving two nodes never double-fire the same tick
 * would need two Spring contexts racing on one row, which is what
 * {@code org.quartz.impl.jdbcjobstore.StdRowLockSemaphore}'s decompiled
 * {@code SELECT ... FOR UPDATE} (recorded in ADR for P-132) already establishes at the SQL
 * level - this test's job is to prove the wiring around that mechanism actually runs a job at
 * all, since a clustered {@code JobStore} misconfigured any other way tends to fail silently
 * (a scheduler with no triggers, rather than an exception).
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuartzClusteringAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        QuartzClusteringAutoConfiguration.class,
                        QuartzAutoConfiguration.class))
                .withBean(
                        "countingJobDetail",
                        JobDetail.class,
                        () -> JobBuilder.newJob(CountingJob.class)
                                .withIdentity("counting-job")
                                .storeDurably()
                                .build())
                .withBean(
                        "countingJobTrigger",
                        Trigger.class,
                        () -> TriggerBuilder.newTrigger()
                                .forJob("counting-job")
                                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(1))
                                .startNow()
                                .build())
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.flyway.locations=classpath:db/migration/scheduling-quartz");
    }

    @Test
    void aJobRegisteredAsABeanIsPickedUpAndFiredByTheClusteredJdbcJobStore() {
        EXECUTIONS.set(0);
        contextRunner()
                .run(context -> await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(EXECUTIONS.get())
                                .as("the clustered JDBC JobStore should have fired the job at least once")
                                .isGreaterThan(0)));
    }

    /** Package-visible only because Quartz instantiates it by reflection; needs no state of its own. */
    public static class CountingJob implements Job {

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            EXECUTIONS.incrementAndGet();
        }
    }
}
