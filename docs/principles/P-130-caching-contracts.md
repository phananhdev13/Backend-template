# P-130 — Caching contracts are declared, not configured

| | |
|---|---|
| **Layer** | application |
| **Enforced by** | `CacheContractRules.everyCacheContractPairsWithASpringCacheAnnotation()`, `CacheContractRules.cacheNamesAgreeOnBackendAndTtl()`, `CacheContractRules.distributedPersonalDataCachesCarryAnAdr()` in `libs/arch-test` |
| **Annotations** | `@CacheContract`, `@Adr` |
| **Guide** | [skill: caching](../../.claude/skills/caching/SKILL.md) |

## Rule

A cached method declares `@CacheContract` beside the real Spring Cache annotation that does
the caching: where the entry lives (`CacheBackend.LOCAL` or `DISTRIBUTED`), how long it may
be served, and whether it carries personal data. Every method that reads, writes or evicts
the same cache name agrees on backend and TTL — the contract is one thing, not whatever each
call site happened to configure.

## Why

**A cache is a second copy of an answer, and every fact about that copy that is not written
down drifts.** In most codebases a cache's backend and TTL live in whichever `@Cacheable`
annotation someone wrote first, and the next method that touches the same cache name copies
it — or doesn't. `libs/caching-support` reads the same declaration this principle requires to
build the `CacheManager`, so the contract and the runtime cannot disagree the way a
hand-copied annotation and a forgotten one can.

**`LOCAL` and `DISTRIBUTED` are not the same promise, and choosing wrong fails differently
depending on direction.** A `LOCAL` (Caffeine) cache is instance-private: an eviction on one
pod leaves every other pod serving the stale value until its own TTL expires, and a rolling
deploy briefly runs old and new instances agreeing on nothing. A `DISTRIBUTED` (Redis) cache
costs a network hop and an operational dependency per access — an outage in the cache is now
an outage in every path that reads it, unless that path tolerates a fallback. Neither is
wrong; the wrong one for a given query is a decision worth a reviewer seeing, not an
accident of whichever `CacheManager` happened to be `@Primary`.

**Two methods sharing a cache name are declaring one cache, not two.** A query and the
eviction that invalidates it both have to agree on what "this cache" means — if the query
says `DISTRIBUTED` with a five-minute TTL and the eviction was written against a `LOCAL`
cache of the same name, the eviction silently does nothing to the cache readers actually
see. This is exactly the kind of two-sided bug [P-042](P-042-event-handlers-delivery-contract.md)
exists to catch on the messaging side, checked here the same way: across the whole import,
not from either method alone.

**Caching personal data is a second retention policy, and `DISTRIBUTED` makes it a second
system's.** An entry cached locally dies with the JVM that cached it; an entry cached in
Redis is visible to anything that can reach that Redis, for as long as the TTL says,
independent of the system of record's own access controls and erasure story. That
combination — personal data, replicated, outside the primary datastore — is not a decision a
method should make silently.

## In code

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

The write that makes the cached answer wrong evicts it, declaring the same name, backend and
TTL the query does:

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

Wrong — a contract with no Spring annotation behind it caches nothing, and describes a
cache that does not exist:

```java
@CacheContract(name = "agents.summary-by-id", backend = CacheBackend.LOCAL, ttlSeconds = 30)
public Optional<AgentSummary> byId(String agentId) { … }   // no @Cacheable — never populated
```

## Enforcement

`CacheContractRules.everyCacheContractPairsWithASpringCacheAnnotation()` fails a method
carrying `@CacheContract` with no `@Cacheable`, `@CachePut` or `@CacheEvict`:

```
AgentSummaryQuery.byId declares @CacheContract but no @Cacheable, @CachePut or @CacheEvict.
Add one naming the same cache, or remove the contract.
See docs/principles/P-130-caching-contracts.md
```

`CacheContractRules.cacheNamesAgreeOnBackendAndTtl()` fails across the whole import when two
methods declare the same cache name with a different backend or TTL.

`CacheContractRules.distributedPersonalDataCachesCarryAnAdr()` fails a `@CacheContract` with
`containsPersonalData = true` and `backend = DISTRIBUTED` carrying no `@Adr`.

## Deviating

A cache with no eviction path at all — served stale for up to `ttlSeconds` and never
invalidated early — is a legitimate, common choice for data that changes rarely or where
staleness is cheap. It needs no `@Adr`; the TTL alone is the recovery bound.

`LOCAL` caching of personal data carries no enforced obligation beyond the contract itself:
it dies with the instance, and never leaves the process that cached it. `DISTRIBUTED`
caching of personal data with no `@Adr` is what this principle refuses.

Related: [P-051](P-051-remote-call-resilience.md) for `CacheBackend.DISTRIBUTED` calls
declared as `@OutboundAdapter(kind = AdapterKind.CACHE)`, which inherit the same
timeout-and-retry obligation as any other remote call.
