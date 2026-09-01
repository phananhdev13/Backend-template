# ADR-0013 — Two cache backends behind one Spring Cache abstraction

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-01 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

A cached query needs exactly one of two different promises. Some answers only need to survive
within one instance for a short time - a hot-path read whose staleness costs nothing a user would
notice. Others must agree across every instance of a service, because a rolling deploy or a load
balancer means the next request for the same key can land anywhere, and two instances disagreeing
on a cached answer is a bug a user can observe directly (a stale price, a permission that was just
revoked). No single cache technology serves both cheaply: an in-process cache cannot be
instance-shared without becoming a network call, and a network cache cannot be instance-local
without adding a hop nothing needed.

Picking one backend platform-wide forces every query into the wrong shape half the time. Caffeine
everywhere means a distributed invalidation problem gets solved with TTL alone, or bolted on as
pub/sub between instances - reinventing what Redis already does. Redis everywhere means a
per-instance-safe read like "the current feature-flag set" or "this request's resolved tenant
config" pays a network round trip and adds a dependency an outage in Redis now takes down.

`libs/kernel`'s `CacheBackend` enum (`LOCAL`, `DISTRIBUTED`) and `@CacheContract` already exist to
make this a declared choice per query rather than a platform-wide default - see
[P-130](../principles/P-130-caching-contracts.md). This ADR is the decision behind that principle:
why the platform supports both, through one abstraction, rather than standardising on one.

## Decision

**`libs/caching-support` composes a Caffeine-backed `CacheManager` and a Redis-backed
`CacheManager` behind one `CompositeCacheManager`, both driven by Spring's `@Cacheable` /
`@CachePut` / `@CacheEvict`, and a service adds whichever dependency the `@CacheContract`s it
writes actually need.**

- `CacheBackend.LOCAL` resolves to Caffeine, `DISTRIBUTED` to Redis. Neither is the platform
  default; the choice is made once per cache name, in code, reviewed the same way any other
  behavioural decision is.
- The `CacheManager` a `@Cacheable` method sees is always the same bean, `CompositeCacheManager`,
  regardless of which backend actually serves a given cache name. Call sites never know or care
  which technology answers them - only the `@CacheContract` declares that.
- A service that never adds `spring-boot-starter-data-redis` gets no `DISTRIBUTED` caches and
  pays nothing for Redis - `@ConditionalOnClass` keeps each backend's configuration inert until
  its dependency is present, the same shape `messaging-support` uses for Kafka and AMQP.
- The Redis-backed manager is built with `immediateWrites()` rather than the library default,
  making eviction and put synchronous. A distributed cache exists to be relied on for cross-
  instance agreement; a fire-and-forget eviction would leave that agreement racy against the very
  reads it exists to protect.

## Consequences

**Good** — Every query gets the backend that actually fits it, decided in the one place a
reviewer already looks for behavioural decisions - the method's annotations - rather than a
platform-wide compromise that is wrong for whichever half of the queries did not shape the
default. A service with no cross-instance caching need never takes a Redis dependency at all.

**Bad** — Two cache technologies is two things to operate, two failure modes to reason about, and
two things a new engineer must learn instead of one. `CacheContractRules.cacheNamesAgreeOnBackendAndTtl`
exists specifically because a query and its eviction disagreeing on backend is now a possible
mistake that a single-backend platform could not make.

**Neutral** — `CompositeCacheManager` with `setFallbackToNoOpCache(false)` means a cache name no
delegate declared fails loudly rather than silently caching nothing - a deliberate trade of a
possible startup failure for never shipping a `@Cacheable` that quietly never caches.

## Alternatives considered

### Redis only, everywhere

The simpler platform: one technology, one operational surface, cross-instance-correct by
construction for every cache. It lost on cost imposed on the common case - a per-instance-safe
read that will never need to agree across instances still pays a network hop and takes a
dependency on Redis being reachable, for no correctness gained. It also does not remove the two-
technology problem so much as move it: `messaging-support` still needs Redis-shaped connection
handling as a new operational dependency every service now carries, whether or not that service's
queries need cross-instance agreement.

### Caffeine only, everywhere

The cheaper platform for the common case, and correct for anything that tolerates per-instance
staleness. It lost because it cannot express the queries that must agree across instances at all -
the only paths to that would be reinventing distributed invalidation on top of Caffeine (pub/sub
between instances, or a shared version counter), which is Redis with extra steps and none of its
operational maturity.

### A single `CacheBackend` chosen per service rather than per query

Splits the difference at the wrong granularity: a service serving both cross-instance-sensitive
and per-instance-safe queries would still be forced to pick one backend for all of them, or fall
back to per-query configuration anyway - at which point the per-service choice adds a layer of
indirection with no benefit over declaring the backend where the query itself is defined.

## Revisit when

A third cache shape is needed that neither Caffeine nor Redis serves well - a write-through cache
in front of a slower store, or a cache that must survive a Redis outage by falling back to stale
local data. Revisit `immediateWrites()` if a distributed cache's write throughput becomes a
measured bottleneck; the fix there is a cache-by-cache opt-out, not reverting the platform default.
