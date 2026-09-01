package com.acme.caching;

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
}
