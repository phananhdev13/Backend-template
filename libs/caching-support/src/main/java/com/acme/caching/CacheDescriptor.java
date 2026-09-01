package com.acme.caching;

import com.acme.kernel.cache.CacheBackend;
import com.acme.kernel.cache.CacheContract;
import java.time.Duration;

/**
 * A {@link CacheContract} read off a method and turned into data.
 *
 * <p>The annotation is the declaration; this is the parsed form the {@code CacheManager} builders
 * work from. Keeping the two apart means a builder can be handed a descriptor in a unit test
 * without an annotated method to hang it on, the same reason {@code messaging-support} keeps
 * {@code StreamDescriptor} apart from {@code @EventContract}.
 *
 * @param name the cache name every {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} for
 *     this cache shares
 * @param backend where entries live
 * @param ttl how long an entry may be served before it must be recomputed
 * @param containsPersonalData whether cached entries carry personal data
 */
public record CacheDescriptor(String name, CacheBackend backend, Duration ttl, boolean containsPersonalData) {

    /** The declared contract turned into a descriptor. */
    public static CacheDescriptor from(String name, CacheContract contract) {
        return new CacheDescriptor(
                name, contract.backend(), Duration.ofSeconds(contract.ttlSeconds()), contract.containsPersonalData());
    }
}
