package com.acme.caching;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deployment-shaped half of caching configuration.
 *
 * <p>Everything that decides what a cache <em>is</em> - its name, its backend, its TTL, whether it
 * carries personal data - lives on {@code @CacheContract}, not here. What is legitimately a
 * deployment concern is only where to look for those declarations.
 */
@ConfigurationProperties("acme.caching")
public class CachingProperties {

    private List<String> basePackages = new ArrayList<>();

    private Duration commandTimeout = Duration.ofMillis(250);

    /**
     * Packages scanned for {@code @CacheContract} methods.
     *
     * <p>Empty means the scan is skipped and no cache is provisioned with a declared TTL or
     * backend - a service that caches nothing should not have to declare an empty package list.
     *
     * @return the base packages to scan
     */
    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }

    /**
     * How long a distributed cache command may take before it counts as unavailable.
     *
     * <p>Applied only when the service has not set {@code spring.data.redis.timeout} itself, and it
     * exists because Lettuce's own default is <strong>60 seconds</strong>. At that budget a Redis
     * that stops answering holds every request thread that touches a cache, which is exactly the
     * failure mode P-051 is about - reached, ironically, through the {@code @Cacheable} shape P-130
     * recommends.
     *
     * <p>250ms rather than something larger: a cache exists to be faster than recomputing. A cache
     * lookup slower than that has already lost its argument, so waiting longer buys nothing but the
     * chance to wait.
     *
     * @return the command timeout for distributed caches
     */
    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }
}
