package com.acme.caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.caching.fixture.SampleCachedService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the composite {@code CacheManager} actually caches, against a real Redis - not merely
 * that the context starts. A context-loading test alone would not catch a serializer that cannot
 * round-trip a value, or a TTL that never reaches the broker; those only show up when something
 * is written and read back.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class CachingSupportAutoConfigurationTest {

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
    void localCacheServesRepeatedCallsFromCaffeineWithoutInvokingTheMethodAgain() {
        contextRunner().run(context -> {
            SampleCachedService service = context.getBean(SampleCachedService.class);

            assertThat(service.localValue("a")).isEqualTo("local-a");
            assertThat(service.localValue("a")).isEqualTo("local-a");

            assertThat(service.localCallCount()).isEqualTo(1);
        });
    }

    @Test
    void distributedCacheRoundTripsThroughARealRedisContainer() {
        contextRunner().run(context -> {
            SampleCachedService service = context.getBean(SampleCachedService.class);

            assertThat(service.distributedValue("b")).isEqualTo("distributed-b");
            assertThat(service.distributedValue("b")).isEqualTo("distributed-b");

            assertThat(service.distributedCallCount())
                    .as("the second call was served from Redis, not the method body")
                    .isEqualTo(1);

            CacheManager cacheManager = context.getBean(CacheManager.class);
            assertThat(cacheManager
                            .getCache("caching.sample-distributed")
                            .get("b")
                            .get())
                    .isEqualTo("distributed-b");
        });
    }

    @Test
    void evictingADistributedEntryForcesTheNextCallToRecompute() {
        contextRunner().run(context -> {
            SampleCachedService service = context.getBean(SampleCachedService.class);

            assertThat(service.distributedValue("c")).isEqualTo("distributed-c");
            service.evictDistributed("c");
            assertThat(service.distributedValue("c")).isEqualTo("distributed-c");

            assertThat(service.distributedCallCount()).isEqualTo(2);
        });
    }

    @Test
    void cacheManagerFailsFastWhenNoBackendIsAvailable() {
        assertThatThrownBy(() -> new CachingSupportAutoConfiguration().cacheManager(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no cache backend is available");
    }
}
