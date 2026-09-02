---
name: caching
description: Cache a query in this repo - choosing CacheBackend.LOCAL (Caffeine) versus DISTRIBUTED (Redis), declaring @CacheContract beside a real Spring Cache annotation, keeping a query and its eviction in agreement. Use whenever a change involves @Cacheable, @CacheEvict, @CachePut, a CacheManager, or when a CacheContractRules failure needs interpreting.
---

# Caching

The decisions that matter about a cache - where the entry lives, how long it may be served -
are usually whatever `@Cacheable` someone wrote first, copied or drifted from by the next call
site that touches the same cache name. Here they live on the method, in `@CacheContract`, beside
the real Spring Cache annotation that does the work. `libs/caching-support` builds the
`CacheManager` from the same declarations; the contract and the runtime cannot disagree the way a
hand-copied annotation and a forgotten one can.

## Design the contract, in this order

### 1. `LOCAL` or `DISTRIBUTED`?

Not interchangeable, and wrong in different directions:

| | `LOCAL` (Caffeine) | `DISTRIBUTED` (Redis) |
|---|---|---|
| Lives | in this instance's heap | in Redis, shared across every instance |
| Cost per access | none | a network hop |
| An eviction on one pod | leaves every other pod stale until its own TTL | is visible to every instance immediately |
| Fails when | never (it's memory) | Redis is unreachable - reads and writes degrade to a cache miss, evictions propagate (see below) |
| Right for | per-instance hot paths, rarely-invalidated data | anything where two instances must agree on the answer |

A rolling deploy briefly runs old and new instances side by side; a `LOCAL` cache with an
eviction path means they can briefly disagree on purpose. If that is not acceptable for this
query, it needs `DISTRIBUTED`.

### 2. How long can a stale answer survive?

`ttlSeconds` is the entire staleness budget for a cache with no eviction path, and the outer bound
even for one that has eviction - a missed or racing eviction still only lasts this long. Short
enough that "worst case, stale for `N` seconds" is a sentence you'd actually say to whoever asked
for this query.

### 3. Does the cached answer contain personal data?

`containsPersonalData = true` on a `DISTRIBUTED` cache needs an `@Adr`
(`CacheContractRules.distributedPersonalDataCachesCarryAnAdr`) - it is now a second store of that
data, with its own retention (the TTL) and its own access surface (anything that can reach that
Redis), independent of the system of record's. `LOCAL` carries no such obligation: it dies with
the JVM that cached it.

## Declare it beside the real annotation

`@CacheContract` alone caches nothing - it is documentation with no Spring annotation behind it,
and `CacheContractRules.everyCacheContractPairsWithASpringCacheAnnotation` fails exactly that:

```java
@ReadModel(id = "QRY-AGT-001")
@Transactional(readOnly = true)
public class AgentSummaryQuery {

    @CacheContract(name = "agents.summary-by-id", backend = CacheBackend.LOCAL, ttlSeconds = 30)
    @Cacheable(cacheNames = "agents.summary-by-id", key = "#agentId")
    public Optional<AgentSummary> byId(String agentId) {
        return jdbc.sql("select ... where a.id = :agentId").param("agentId", agentId)
                .query(AgentSummary.class).optional();
    }
}
```

The write that makes the cached answer wrong evicts it, declaring the **same name, backend and
TTL** the query does - `CacheContractRules.cacheNamesAgreeOnBackendAndTtl` checks this across the
whole import, not just within one class, because the query and the eviction are two sides of one
cache and only ever look like two independent methods:

```java
@UseCase(id = "UC-AGT-003", value = "A platform engineer activates an agent version")
@Transactional
public class ActivateAgentVersionService implements ActivateAgentVersionUseCase {

    @CacheContract(name = "agents.summary-by-id", backend = CacheBackend.LOCAL, ttlSeconds = 30)
    @CacheEvict(cacheNames = "agents.summary-by-id", key = "#command.agentId()")
    @Override
    public void activateVersion(ActivateAgentVersionCommand command) { … }
}
```

Both methods name `"agents.summary-by-id"`. If the eviction had said `DISTRIBUTED` while the query
says `LOCAL`, the eviction would silently do nothing to the cache readers actually see - the build
fails before that ships.

## Redis eviction is synchronous here, and that took a real bug to find

Spring Data Redis's default `RedisCacheWriter` writes and evicts **asynchronously** whenever the
connection factory supports it, which Lettuce - Boot's default - always does: `evict()` returns
as soon as the `DEL` is queued, not once Redis has actually applied it. A test that evicts and
immediately reads back the same key can see the value it just evicted, intermittently, because
the two race. `libs/caching-support`'s `DistributedCacheConfiguration` builds its `RedisCacheWriter`
with `immediateWrites()` specifically to close this race - do not remove it, and do not build a
second `RedisCacheManager` elsewhere without the same setting. If you ever see a distributed cache
test that reads stale data right after an evict, this is the first thing to check.

## When Redis is down

`caching-support` decides this once, so you do not: a **250ms** command timeout
(`acme.caching.command-timeout`, unless you set `spring.data.redis.timeout` yourself) and
`DegradingCacheErrorHandler`.

- A failed **read** or **write** degrades to a cache miss. The caller gets the right answer from
  the method body, slower. A cache is an optimisation; losing it should cost latency, not
  availability.
- A failed **eviction** propagates. That asymmetry is deliberate: your write succeeded and the
  cache is still holding the old value, so swallowing the failure serves data known to be wrong,
  to everyone, for the rest of the TTL. A failed read costs one recomputation; a failed eviction
  is a correctness bug.

Neither is theoretical. `CacheOutageIntegrationTest` pauses a real Redis container mid-call - a
*stopped* container refuses connections and fails instantly, which proves nothing, while a paused
one accepts commands and never answers. Reverting either half of the fix makes that test block for
**123 seconds** and throw `RedisCommandTimeoutException: Command timed out after 1 minute(s)`,
which is Lettuce's default and what every service had before.

## Do you need an adapter instead?

`@Cacheable` on a use case is the default and it is safe, because the module above supplies the
timeout and the failure policy. Reach for an `@OutboundAdapter(kind = AdapterKind.CACHE)` behind
an `@OutputPort` when the cache is a dependency in its own right rather than an optimisation of
one query - cache-aside with its own logic, a negative-result policy, a second lookup key. Then
P-051's `remoteCallsDeclareTimeouts` applies as it does to any other remote call, and you state
the numbers on the adapter. See [P-130](../../../docs/principles/P-130-caching-contracts.md) for
the comparison.

## Checklist

- [ ] `LOCAL` unless two instances must agree on the answer, or a network hop is acceptable
- [ ] `ttlSeconds` is a staleness budget you could say out loud
- [ ] `@CacheContract` sits beside a real `@Cacheable`, `@CachePut` or `@CacheEvict`
- [ ] every method touching this cache name agrees on backend and TTL
- [ ] the write that invalidates this query evicts the same name
- [ ] `containsPersonalData = true` with `DISTRIBUTED` carries an `@Adr`
- [ ] a `CacheManager` your own code builds directly (rare) also sets `immediateWrites()`
- [ ] a `DISTRIBUTED` cache that is a dependency rather than an optimisation is behind an
      `@OutboundAdapter(kind = CACHE)`, not a bare `@Cacheable`
