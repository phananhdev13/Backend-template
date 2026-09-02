package com.acme.caching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * What happens to a cached call when the cache itself is down.
 *
 * <p>Spring's default is {@code SimpleCacheErrorHandler}, which rethrows every one of these. With
 * a {@code DISTRIBUTED} cache that turns a Redis outage into an outage of every use case that
 * caches - the failure P-051 exists to prevent, arriving through the very shape P-130 recommends.
 * A cache is an optimisation; losing it should cost latency, not availability.
 *
 * <p><strong>Reads and writes degrade; evictions do not.</strong> That asymmetry is the whole
 * design and it is not symmetry-for-its-own-sake that is missing:
 *
 * <ul>
 *   <li>A failed <em>get</em> costs one recomputation. The caller gets the right answer, slower.
 *   <li>A failed <em>put</em> costs one uncached result. The caller already has the right answer.
 *   <li>A failed <em>evict</em> or <em>clear</em> is different in kind. The caller has just
 *       changed the underlying data, and the cache is still holding the old value - so swallowing
 *       it serves data that is known to be wrong, for the whole TTL, to everyone. That is a
 *       correctness failure, not a performance one, and it propagates.
 * </ul>
 *
 * <p>Degrading is only safe because the wait is bounded: see {@code CachingProperties} for the
 * command timeout that makes "the cache is down" a fast answer rather than a slow one.
 */
public final class DegradingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(DegradingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache '{}' unavailable on read; recomputing. {}", cache.getName(), exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache '{}' unavailable on write; result not cached. {}", cache.getName(), exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        throw exception;
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        throw exception;
    }
}
