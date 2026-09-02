# ADR-0022 — A distributed cache's timeout and failure policy belong to caching-support, not to each caller

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-02 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

[P-130](../principles/P-130-caching-contracts.md) and
[P-051](../principles/P-051-remote-call-resilience.md) contradicted each other, and the
contradiction had a live consequence.

P-130's worked examples put `@CacheContract` with `@Cacheable` directly on a `@UseCase` or a
`@ReadModel`. With `backend = DISTRIBUTED` that issues a Redis command from the application layer
through a Spring cache proxy — a network call with no `@OutboundAdapter` anywhere. P-051 requires
every remote call to declare a connect and read timeout and a stated failure behaviour, and
`ResilienceRules.remoteCallsDeclareTimeouts` can only inspect `@OutboundAdapter` classes. So the
obligation P-130 said was "inherited" attached to nothing, and the rule could never notice.

P-041's "Deviating" section gave a third answer for the same situation — one adapter of
`kind = PERSISTENCE` composing a cache collaborator — which `AdapterKind.isRemote()` excludes, so
that shape escaped P-051 as well.

Two things were then measured rather than assumed, against a real Redis container paused
mid-call:

- **Lettuce's default command timeout is 60 seconds.** Nothing in this repository set
  `spring.data.redis.timeout`, so a Redis that accepted connections and stopped answering held the
  calling thread for a full minute.
- **Spring Cache's default `CacheErrorHandler` rethrows.** After that minute the call failed with
  `QueryTimeoutException`.

Together: a Redis incident became a 60-second hang and then an outage in every use case that
cached, arriving through the exact shape P-130 recommends. The test that demonstrates it takes
123 seconds with the fix reverted and 6 seconds with it in place.

## Decision

**Both shapes stay legitimate, and the resilience obligation is discharged where the numbers can
actually be stated once.**

`@Cacheable` on a use case or read model remains the default for caching a query, including
`DISTRIBUTED`. `caching-support` supplies what P-051 asks for:

- a **250ms** command timeout (`acme.caching.command-timeout`), applied only when the service has
  not set `spring.data.redis.timeout` itself, so an explicit choice always wins;
- `DegradingCacheErrorHandler`, which degrades reads and writes to a cache miss and **propagates
  evictions and clears**.

An `@OutboundAdapter(kind = AdapterKind.CACHE)` behind an `@OutputPort` remains available, and is
the right shape when the cache is a dependency in its own right rather than an optimisation of one
query — cache-aside with its own logic, a negative-result policy, a second lookup key. There
`ResilienceRules.remoteCallsDeclareTimeouts` applies unchanged.

**Evictions do not degrade, and that asymmetry is the substance of the decision.** A failed read
costs one recomputation and the caller still gets the right answer. A failed eviction leaves the
cache serving a value the caller has just changed, to every instance, for the rest of the TTL.
The first is a latency problem; the second is a correctness problem, so it reaches the caller.

## Consequences

**Good** — The `@Cacheable` shape is now safe by construction: no caller can forget the timeout,
because no caller supplies it. The numbers live in one place instead of being repeated at every
call site, which is what P-051 wanted and what a per-adapter obligation could not deliver for a
proxy-based cache. A Redis incident costs latency and a raised cache-miss rate rather than
availability. Both properties are proven against a real, deliberately hung Redis rather than
argued from documentation.

**Bad** — Degrading is silent by design, so a service whose Redis is failing keeps answering while
recomputing everything, and will look healthy on availability metrics while its latency and
database load climb. That is the trade this ADR chooses, and it makes the cache-miss rate and the
`DegradingCacheErrorHandler` warning a monitoring obligation rather than an optional dashboard.
The 250ms default is also a judgement, not a measurement: a cache legitimately slower than that —
a large serialised value over a congested link — will degrade under load rather than wait, and
such a service must raise `acme.caching.command-timeout` deliberately.

**Neutral** — `ResilienceRules` still cannot see the `@Cacheable` shape, and this ADR does not
pretend otherwise. The guarantee for that shape is a runtime one, tested by
`CacheOutageIntegrationTest`, not a build-time one. That is stated in both principles so the next
reader does not go looking for a rule that cannot exist.

## Alternatives considered

### Require every DISTRIBUTED cache to sit behind an `@OutboundAdapter(kind = CACHE)`

This would make `ResilienceRules` see every distributed cache, and needs no new runtime
machinery. Rejected: it discards Spring Cache's declarative model for the common case, where
caching genuinely is an implementation detail of one query. Every cached read would grow a port,
an adapter and a test double, and the `@CacheContract`/`@Cacheable` pairing this repository
already enforces would lose its purpose. The cost falls on the ninety per cent of caches that are
plain memoisation.

### Loosen P-051 to exempt caches

The shortest edit: say that a cache is not a remote call for the purposes of the timeout rule.
Rejected because it is false. A Redis command is a network round trip that can hang, which is the
entire subject of P-051, and the measured 60-second hang is exactly what the principle exists to
prevent. Exempting the case would have left the defect in place and removed the language needed
to describe it.

### Fail closed on a cache read, as the OPA client does

Consistent with [ADR-0021](0021-keycloak-sso-and-opa-as-the-standard-authorization-sidecar.md),
where an unreachable policy sidecar denies. Rejected because the two answer different questions.
A policy engine that cannot be reached leaves the caller's permission unknown, and guessing is a
breach. A cache that cannot be reached leaves the caller's answer perfectly knowable — it is in
the database — so refusing to serve it trades an outage for nothing.

### Degrade evictions too, for symmetry

Simpler to explain and to implement. Rejected: it is the one case where continuing serves data the
system knows to be wrong. The failure is not "this call was slower than hoped" but "this cache now
disagrees with the database until its TTL expires", and the caller is the only party in a position
to retry or to refuse to commit.

## Revisit when

Spring Data Redis changes Lettuce's default command timeout (re-measure against this ADR's own
recorded 60 seconds); Spring Framework gains a first-class per-cache resilience declaration, which
would let the budget sit on `@CacheContract` where the rest of the cache's semantics already live
and make this module-level policy unnecessary; or a service's measured cache latency makes 250ms
the wrong default for the platform rather than for that one service.
