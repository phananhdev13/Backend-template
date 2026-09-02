package com.acme.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the ShedLock wiring is a real mutual-exclusion mechanism against a real Postgres, not
 * merely that the {@code LockProvider} bean exists.
 *
 * <p>Calls {@link LockProvider#lock} directly rather than going through a proxied
 * {@code @Scheduled} method - the same real row-lock either way, without needing a second
 * Spring context to simulate a second instance.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShedLockAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        ShedLockAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.flyway.locations=classpath:db/migration/scheduling-shedlock");
    }

    @Test
    void aSecondInstanceCannotAcquireALockAlreadyHeld() {
        contextRunner().run(context -> {
            LockProvider lockProvider = context.getBean(LockProvider.class);
            LockConfiguration config =
                    new LockConfiguration(Instant.now(), "shared-nightly-job", Duration.ofMinutes(5), Duration.ZERO);

            var firstInstanceLock = lockProvider.lock(config);
            assertThat(firstInstanceLock)
                    .as("the first instance to ask should acquire the lock")
                    .isPresent();

            var secondInstanceLock = lockProvider.lock(config);
            assertThat(secondInstanceLock)
                    .as("a second instance racing the same tick must not also acquire it")
                    .isEmpty();

            firstInstanceLock.ifPresent(SimpleLock::unlock);

            var afterRelease = lockProvider.lock(config);
            assertThat(afterRelease)
                    .as("once released, the next instance to ask acquires it")
                    .isPresent();
            afterRelease.ifPresent(SimpleLock::unlock);
        });
    }
}
