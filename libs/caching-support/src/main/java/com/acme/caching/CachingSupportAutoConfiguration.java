package com.acme.caching;

import com.acme.kernel.cache.CacheBackend;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires a {@code CacheManager} per {@link CacheBackend}, and only the parts a service actually
 * pulled in.
 *
 * <p>Both dependencies this composes are optional, so a service that adds only Caffeine, only
 * Redis, or neither all start from the same library - the same shape {@code messaging-support}
 * uses for Kafka and AMQP. What ties the two backends' caches together into the one
 * {@code CacheManager} Spring Cache actually calls is {@link CompositeCacheManager}: every
 * {@code @Cacheable} asks it for a cache by name, and it asks whichever delegate declared that
 * name.
 */
@AutoConfiguration
@EnableConfigurationProperties(CachingProperties.class)
@EnableCaching
public class CachingSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    List<CacheDescriptor> cacheDescriptors(CachingProperties properties) {
        return CacheContracts.scan(properties.getBasePackages());
    }

    /**
     * The one {@code CacheManager} bean Spring Cache's proxies see.
     *
     * <p>{@code delegates} collects every backend-specific manager this autoconfiguration built -
     * Spring excludes a bean under construction from a collection injected into itself, so this
     * cannot see its own output. Failing here with a clear cause beats the alternative: a
     * {@code CacheManager} with no delegates would accept every {@code @Cacheable} call and
     * silently cache nothing, which is a correctness bug wearing a working build.
     */
    @Bean
    @Primary
    CacheManager cacheManager(List<CacheManager> delegates) {
        if (delegates.isEmpty()) {
            throw new IllegalStateException(
                    "caching-support is on the classpath but no cache backend is available. Add "
                            + "com.github.ben-manes.caffeine:caffeine for CacheBackend.LOCAL, or "
                            + "org.springframework.boot:spring-boot-starter-data-redis (and "
                            + "spring-boot-starter-json) for CacheBackend.DISTRIBUTED.");
        }
        CompositeCacheManager composite = new CompositeCacheManager();
        composite.setCacheManagers(delegates);
        // A cache name no delegate declared is a @CacheContract nobody wrote, or a typo in one
        // that was. Either way it should fail the call, not hand back a cache that never stores
        // anything - that is a correctness bug that passes every test because a no-op cache never
        // returns wrong data, only no data.
        composite.setFallbackToNoOpCache(false);
        return composite;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Caffeine.class)
    static class LocalCacheConfiguration {

        @Bean
        CacheManager localCacheManager(List<CacheDescriptor> descriptors) {
            SimpleCacheManager manager = new SimpleCacheManager();
            List<CaffeineCache> caches = descriptors.stream()
                    .filter(descriptor -> descriptor.backend() == CacheBackend.LOCAL)
                    .map(LocalCacheConfiguration::toCaffeineCache)
                    .toList();
            manager.setCaches(caches);
            return manager;
        }

        private static CaffeineCache toCaffeineCache(CacheDescriptor descriptor) {
            return new CaffeineCache(
                    descriptor.name(),
                    Caffeine.newBuilder().expireAfterWrite(descriptor.ttl()).build());
        }
    }

    /**
     * What a cache outage costs, decided once here rather than per service.
     *
     * <p>This is what lets P-130's {@code @Cacheable}-on-a-use-case shape coexist with P-051: the
     * remote call is still a remote call, but its timeout and its failure behaviour are supplied by
     * this module instead of by every caller. A service that wants the call to be an explicit
     * dependency instead writes an {@code @OutboundAdapter(kind = CACHE)} and gets P-051's own rule;
     * both shapes are legitimate, and neither leaves the budget unstated.
     *
     * <p>{@code @ConditionalOnMissingBean} on the interface, not this method: Spring permits exactly
     * one {@link CachingConfigurer}, so a service that declares its own must win rather than clash.
     */
    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    CachingConfigurer cachingResilience() {
        return new CachingConfigurer() {
            @Override
            public CacheErrorHandler errorHandler() {
                return new DegradingCacheErrorHandler();
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class DistributedCacheConfiguration {

        /**
         * Bounds the wait, which is what makes degrading safe rather than merely quiet.
         *
         * <p>Applied only when the service left {@code spring.data.redis.timeout} unset, so an
         * explicit choice always wins. Without it Lettuce waits its own default of 60 seconds, and
         * {@link DegradingCacheErrorHandler} would turn a hung Redis into a minute of held threads
         * before deciding to carry on - technically available, practically an outage.
         */
        @Bean
        LettuceClientConfigurationBuilderCustomizer cachingCommandTimeout(
                CachingProperties properties, Environment environment) {
            return builder -> {
                if (!environment.containsProperty("spring.data.redis.timeout")) {
                    builder.commandTimeout(properties.getCommandTimeout());
                }
            };
        }

        /**
         * Falls back to a plain mapper only if the service declared none of its own.
         *
         * <p>A service with a web starter already has one, configured with whatever modules its
         * own payloads need; reusing it means a cached value serialises the same way it would if
         * it were ever written to an HTTP response. This exists purely so that a service pulling
         * in only {@code spring-boot-starter-data-redis} - no web starter, no Jackson
         * configuration of its own - still starts.
         */
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper cachingObjectMapper() {
            return JsonMapper.builder().build();
        }

        /**
         * Every distributed cache serialises the same way, decided once here.
         *
         * <p>{@link GenericJacksonJsonRedisSerializer} is the Jackson-3 serialiser - the Jackson-2
         * classes of the same family still exist for back-compat and must never be used, the same
         * rule {@code messaging-support} holds Kafka and AMQP to. Without an explicit serialiser
         * {@code RedisCacheConfiguration} falls back to JDK serialisation, which ties every cached
         * value to the producing JVM's exact class bytes - a rolling deploy across two versions of
         * a value's class would then fail to read entries the other version wrote.
         *
         * <p>{@code immediateWrites()} turns off the write-behind default a {@link RedisCacheWriter}
         * otherwise takes with an async-capable connection factory: without it, a put or an evict
         * returns as soon as the command is queued, not once Redis has actually applied it, so a
         * read immediately after an eviction can still see the value it just evicted. A cache
         * eviction that does not yet hold when the method that triggered it returns is not a cache
         * eviction a caller can rely on.
         */
        @Bean
        CacheManager distributedCacheManager(
                RedisConnectionFactory connectionFactory,
                ObjectMapper objectMapper,
                List<CacheDescriptor> descriptors) {
            RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                            new GenericJacksonJsonRedisSerializer(objectMapper)));
            Map<String, RedisCacheConfiguration> perCache = descriptors.stream()
                    .filter(descriptor -> descriptor.backend() == CacheBackend.DISTRIBUTED)
                    .collect(
                            Collectors.toMap(CacheDescriptor::name, descriptor -> withTtl(defaults, descriptor.ttl())));
            RedisCacheWriter writer =
                    RedisCacheWriter.create(connectionFactory, configurer -> configurer.immediateWrites());
            return RedisCacheManager.builder(writer)
                    .cacheDefaults(defaults)
                    .withInitialCacheConfigurations(perCache)
                    // Without this, RedisCacheManager creates a cache for ANY name asked of it,
                    // using `defaults` - which carries no entryTtl, so entries never expire. A
                    // @Cacheable naming a cache no @CacheContract declared would then be served
                    // from Redis, forever, ignoring both the declared backend and the declared
                    // TTL, while the composite manager's setFallbackToNoOpCache(false) above
                    // reported nothing because the Redis delegate had happily answered. The
                    // undeclared name now fails the call, which is what that comment promises.
                    .disableCreateOnMissingCache()
                    .build();
        }

        private static RedisCacheConfiguration withTtl(RedisCacheConfiguration base, Duration ttl) {
            return base.entryTtl(ttl);
        }
    }
}
