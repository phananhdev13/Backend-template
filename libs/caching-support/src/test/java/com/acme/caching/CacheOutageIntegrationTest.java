package com.acme.caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.caching.fixture.SampleCachedService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * What a cached call does when Redis stops answering - the question P-130 and P-051 disagreed about
 * until this test settled it.
 *
 * <p>The outage is produced by <strong>pausing</strong> the container rather than stopping it, and
 * the difference is the whole point. A stopped container refuses connections, which fails
 * immediately and proves nothing about timeouts. A paused one has its process frozen by the kernel
 * while the socket stays open, so commands are accepted and never answered - which is what a Redis
 * under GC pressure, swapping, or behind a black-holing network actually looks like, and the only
 * version of "down" that can hold a thread for Lettuce's 60-second default.
 *
 * <p>Without {@code caching-support}'s bounded command timeout and
 * {@link DegradingCacheErrorHandler}, the assertions below fail in two different ways: the read
 * blocks for a minute, and then throws.
 */
@Testcontainers(disabledWithoutDocker = true)
class CacheOutageIntegrationTest {

    /** Well under Lettuce's 60s default, comfortably over the 250ms budget plus context overhead. */
    private static final Duration DEGRADES_WITHIN = Duration.ofSeconds(10);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(DataRedisAutoConfiguration.class, CachingSupportAutoConfiguration.class))
                .withBean(SampleCachedService.class, SampleCachedService::new)
                .withPropertyValues(
                        "acme.caching.base-packages=com.acme.caching.fixture",
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getMappedPort(6379));
    }

    @Test
    void aReadStillAnswersQuicklyWhileRedisIsHung() {
        contextRunner().run(context -> {
            SampleCachedService service = context.getBean(SampleCachedService.class);
            assertThat(service.distributedValue("warm")).isEqualTo("distributed-warm");
            int callsBefore = service.distributedCallCount();

            Duration blocked = whilePaused(() -> {
                Instant start = Instant.now();
                String value = service.distributedValue("during-outage");
                assertThat(value)
                        .as("the caller gets the right answer from the method body, not an exception")
                        .isEqualTo("distributed-during-outage");
                return Duration.between(start, Instant.now());
            });

            assertThat(blocked)
                    .as("a cache lookup that cannot be answered must fail fast, not wait out "
                            + "Lettuce's 60-second default")
                    .isLessThan(DEGRADES_WITHIN);
            assertThat(service.distributedCallCount())
                    .as("degrading to a cache miss means the method body ran")
                    .isGreaterThan(callsBefore);
        });
    }

    @Test
    void anEvictionThatCannotBeAppliedIsNotSwallowed() {
        contextRunner().run(context -> {
            SampleCachedService service = context.getBean(SampleCachedService.class);
            service.distributedValue("to-evict");

            whilePaused(() -> {
                assertThatThrownBy(() -> service.evictDistributed("to-evict"))
                        .as("a failed eviction leaves the cache serving data the caller just "
                                + "changed - unlike a failed read, that is a correctness problem "
                                + "and must reach the caller")
                        .isInstanceOf(RuntimeException.class);
                return null;
            });
        });
    }

    /** Runs {@code action} with the Redis container frozen, and always unfreezes it afterwards. */
    private static <T> T whilePaused(ThrowingSupplier<T> action) throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            return action.get();
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
