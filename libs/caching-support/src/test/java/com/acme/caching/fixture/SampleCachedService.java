package com.acme.caching.fixture;

import com.acme.kernel.cache.CacheBackend;
import com.acme.kernel.cache.CacheContract;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

/**
 * A plausible, throwaway cached service - one method per backend - used to prove the
 * autoconfiguration actually caches rather than merely compiles.
 */
public class SampleCachedService {

    private final AtomicInteger localCalls = new AtomicInteger();
    private final AtomicInteger distributedCalls = new AtomicInteger();

    @CacheContract(name = "caching.sample-local", backend = CacheBackend.LOCAL, ttlSeconds = 60)
    @Cacheable(cacheNames = "caching.sample-local")
    public String localValue(String key) {
        localCalls.incrementAndGet();
        return "local-" + key;
    }

    @CacheContract(name = "caching.sample-distributed", backend = CacheBackend.DISTRIBUTED, ttlSeconds = 60)
    @Cacheable(cacheNames = "caching.sample-distributed")
    public String distributedValue(String key) {
        distributedCalls.incrementAndGet();
        return "distributed-" + key;
    }

    @CacheContract(name = "caching.sample-distributed", backend = CacheBackend.DISTRIBUTED, ttlSeconds = 60)
    @CacheEvict(cacheNames = "caching.sample-distributed")
    public void evictDistributed(String key) {
        // Invalidation only; the eviction itself is Spring's, not this method's body.
    }

    public int localCallCount() {
        return localCalls.get();
    }

    public int distributedCallCount() {
        return distributedCalls.get();
    }
}
