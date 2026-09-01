package com.acme.kernel.cache;

/** Where a cache's entries actually live, named for what that implies rather than the vendor. */
public enum CacheBackend {

    /**
     * Process-local, in the JVM heap.
     *
     * <p>The fastest option, because there is no network hop - and the reason it is the
     * default. It is not shared: two instances of the same service disagree, an eviction
     * on one instance leaves the entry stale on every other, and a restart or a rolling
     * deploy discards it. Correct for data an individual instance can afford to see
     * slightly stale, or that is cheap enough to recompute per instance.
     */
    LOCAL,

    /**
     * Shared across every instance, outside the JVM heap.
     *
     * <p>An eviction is visible everywhere at once, and the entry survives a restart of
     * any one instance. The cost is a network hop per access and an operational
     * dependency: the cache is now a system that can be down independently of the
     * service. Correct for data that must agree across instances, or that is expensive
     * enough to recompute that every instance doing it separately matters.
     */
    DISTRIBUTED
}
