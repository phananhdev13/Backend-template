# P-070 — Event semantics are declared, not configured

| | |
|---|---|
| **Layer** | domain |
| **Enforced by** | `EventContractRules.compactedStreamsCarryStateSnapshots()`, `EventContractRules.partitionKeyExistsOnRecord()`, `EventContractRules.everyContractHasASchemaFile()`, `EventContractRules.orderingPromisesRequireAKey()`, `EventContractRules.unboundedChoicesAreJustified()`, `EventContractRules.schemaFilesAgreeWithTheirContract()`, `EventContractRules.eventsArePublishedThroughTheTypedPublisher()` in `libs/arch-test` |
| **Annotations** | `@EventContract`, `@DomainEvent`, `@Adr` |
| **Guide** | [G-030](../guides/G-030-events.md) |

## Rule

Every published event declares its stream, version, partition key, payload kind, retention,
delivery and ordering guarantees, and its schema file, on the event record itself.
`libs/messaging-support` derives broker configuration from that declaration. Nothing
configures a topic by hand.

## Why

**Broker configuration is the wrong home for decisions consumers depend on.** The key that
defines ordering, whether history survives, how many times a message may arrive — these are
contract terms, and in most systems they live in a Terraform module owned by a different
team, or in a `kafka-topics --create` someone ran in 2023. The code that depends on them
cannot see them, review never sees them change, and a topic recreated during a cluster
migration comes back with defaults. Nothing in the codebase notices until the data is wrong.

**Compaction on a stream of facts destroys replay, silently and later.** This is the
canonical failure. `OrderLineAdded` and `OrderLineRemoved` are deltas: current state is the
fold of all of them. Compaction keeps only the latest message per key and deletes the rest,
so after the first compaction run the earlier deltas are gone from the log. Nothing breaks —
live consumers are up to date and see every new message. Months later a new consumer is
deployed and reads from the beginning, or an existing one is reset to recover from a bad
deploy, and it rebuilds state from a single surviving delta per key. The orders it produces
are missing every line but one. The change that caused it was a one-line topic setting made
by someone who was reducing storage costs, and the connection between cause and symptom is
several months and several people wide. Declaring `payload = FACT` makes the combination a
build failure instead.

**A partition key that names a field that no longer exists degrades to random
partitioning.** Rename `orderId` to `orderReference` on the record and, in a
string-configured world, the producer's key extractor returns null, every message is
assigned round-robin, and per-key ordering is gone. `OrderPlaced` and `OrderCancelled` for
the same order land on different partitions and are consumed by different threads. Roughly
one in N orders — N being the partition count — gets processed out of order, so the bug
looks intermittent and customer-specific. Checking the key against the record's actual
components catches this at compile-adjacent time.

**`GLOBAL` ordering is a scalability decision disguised as a correctness one.** It requires a
single partition, which caps throughput at one consumer forever. Teams pick it because it is
the safe-sounding option, and discover the ceiling under load, when changing it means
migrating every consumer. `PER_KEY` is what domains actually need — events about one order,
in order — and it scales.

**A published event is an API.** A consumer in another repository, possibly another team's,
generates code from your schema. Keeping the schema file in `contracts/events/` beside the
code means a breaking change is visible in the diff that causes it. Adding an optional field
is compatible; anything else — removing a field, changing a type, changing the key or the
retention — is a new `version` on a new stream, run in parallel until consumers migrate. You
delete the old stream when it has no consumers, not when the new one works.

**`INFINITE` retention needs an owner.** Someone pays for the storage and someone owns the
replay story. Requiring an ADR is how that conversation happens before the data exists rather
than during the first cost review.

## In code

A fact stream — time-windowed, keyed, ordered per key:

```java
package com.acme.orders.ordering.domain;

@EventContract(
        stream = "orders.order-placed",
        version = 1,
        partitionKey = "orderId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        retentionDays = 30,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/orders.order-placed.v1.json",
        containsPersonalData = false)
public record OrderPlaced(
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        String totalCurrency,
        List<Line> lines,
        Instant occurredAt) implements DomainEvent {

    public record Line(String sku, int quantity, BigDecimal unitPrice) {}
}
```

A state stream — compacted, and therefore a snapshot:

```java
@EventContract(
        stream = "orders.order-state",
        version = 1,
        partitionKey = "orderId",
        payload = PayloadKind.STATE_SNAPSHOT,      // whole current state, so compaction is safe
        retention = StreamRetention.COMPACTED,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/orders.order-state.v1.json")
public record OrderStateChanged(UUID orderId, String status, BigDecimal totalAmount,
                                List<Line> lines, Instant occurredAt) implements DomainEvent {}
```

Wrong — the combination that destroys history:

```java
@EventContract(
        stream = "orders.order-lines",
        partitionKey = "orderId",
        payload = PayloadKind.FACT,                 // a delta …
        retention = StreamRetention.COMPACTED,      // … on a compacted stream. Replay is now impossible.
        schema = "contracts/events/orders.order-lines.v1.json")
public record OrderLineAdded(UUID orderId, String sku, int quantity, Instant occurredAt)
        implements DomainEvent {}
```

Also wrong — a promise the stream cannot keep:

```java
@EventContract(stream = "orders.audit", partitionKey = "orderId",
               ordering = OrderingGuarantee.PER_KEY,
               delivery = DeliveryGuarantee.AT_MOST_ONCE,   // ordering of messages that may vanish
               schema = "contracts/events/orders.audit.v1.json")
```

## Enforcement

`EventContractRules.compactedStreamsCarryStateSnapshots()`:

```
com.acme.orders.ordering.domain.OrderLineAdded declares payload = FACT with
retention = COMPACTED. Compaction deletes superseded messages, so a consumer replaying
this stream cannot reconstruct state. Use TIME_WINDOW, or publish a STATE_SNAPSHOT.
See docs/principles/P-070-event-semantics.md
```

`EventContractRules.partitionKeyExistsOnRecord()` resolves `partitionKey()` against the
record's components and fails on a name that does not exist or is nullable.

`EventContractRules.everyContractHasASchemaFile()` resolves `schema()` from the repository
root and additionally checks the file's field set against the record, so an added required
field cannot ship without a version bump.

`EventContractRules.orderingPromisesRequireAKey()` fails `PER_KEY` with a blank key, and
fails any ordering promise stronger than `NONE` combined with `AT_MOST_ONCE`.

`EventContractRules.unboundedChoicesAreJustified()` fails `INFINITE` without `@Adr`.

At runtime, `libs/messaging-support` provisions from the same declaration — Kafka
`cleanup.policy`/`retention.ms`, RabbitMQ Streams `max-age` — and refuses to start on a
combination the broker cannot honour, such as `COMPACTED` on RabbitMQ Streams, rather than
degrading silently to time-based retention.

## Deviating

Streams owned by another team or another system arrive with whatever semantics they have.
Declare the contract as it *actually is*, not as you would like it, and put the consequences
in an `@Adr` — a consumer written against an aspirational contract fails in ways the
declaration says are impossible, which is the worst debugging position to be in.

`COMPACTED_AND_WINDOWED` trades full-replay reconstruction for bounded storage. It is correct
for a stream that is a rebuildable cache; say where the authoritative copy lives.
