package com.acme.kernel.cache;

import com.acme.kernel.arch.ImplementsPrinciple;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The semantics of a cached query, declared on the method that Spring's own
 * {@code @Cacheable}, {@code @CachePut} or {@code @CacheEvict} caches.
 *
 * <p>Spring Cache abstracts the mechanism; it does not ask what an entry is worth or who
 * else must never see it stale. Those are exactly the decisions that drift when they live
 * only in a property file: someone lowers a TTL for one environment and forgets the
 * others, or moves a cache from local to distributed without noticing it now holds
 * personal data outside the system of record. Declaring them here means the class that
 * defines the cache is also the place a reviewer checks them.
 *
 * <p>This annotation wires nothing by itself - the method still needs a real
 * {@code @Cacheable} (or {@code @CachePut}/{@code @CacheEvict}) naming the same cache, or
 * the contract describes a cache nothing populates. {@code CacheContractRules} checks
 * the pairing.
 *
 * <p>See {@code docs/principles/P-130-caching-contracts.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ImplementsPrinciple("P-130")
public @interface CacheContract {

    /**
     * Cache name, in {@code <context>.<query>} form, for example
     * {@code "agents.summary-by-id"}.
     *
     * <p>Every method that reads, writes or evicts the same logical cache - a
     * {@code @Cacheable} query paired with the {@code @CacheEvict} that invalidates it -
     * names the same value here. {@code CachingContractRules} rejects two methods sharing
     * a name but disagreeing on {@link #backend()} or {@link #ttlSeconds()}: that
     * disagreement is not a style choice, it is two code paths serving different data
     * under the same name.
     */
    String name();

    /** Where entries live. Distributed is the more expensive choice and should be asked for. */
    CacheBackend backend() default CacheBackend.LOCAL;

    /**
     * How long an entry may be served before it must be recomputed.
     *
     * <p>Not a promise that stale data is impossible - a write that bypasses this method,
     * or an eviction that never fires, can still leave one. It is the outer bound on how
     * long a bug like that survives undetected.
     */
    long ttlSeconds() default 300;

    /**
     * Whether cached entries carry personal data.
     *
     * <p>Caching is a second copy of the data, outside the system of record's access
     * controls and retention policy. On {@link CacheBackend#DISTRIBUTED} that copy is
     * visible to anything that can reach the cache, not just this service - which is why
     * that combination requires an {@code @Adr}.
     */
    boolean containsPersonalData() default false;
}
