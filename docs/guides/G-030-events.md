# G-030 — Publishing and consuming events

The design procedure and the checklists are in the `events` skill; the exact Kafka and RabbitMQ
settings each contract option produces are in `.claude/skills/events/references/broker-mapping.md`.
This page is the decision order and the traps.

## Decide, in this order

1. **Fact or state snapshot?** `FACT` states a change and can never be compacted. `STATE_SNAPSHOT`
   carries the whole current value and is the only shape compaction is valid for. Most domain events
   are facts.
2. **Partition key.** The aggregate identifier. It decides partitioning, ordering, and — on a
   compacted stream — identity.
3. **Retention.** `TIME_WINDOW` for facts; `COMPACTED` only for snapshots; `INFINITE` only with an
   `@Adr` naming who owns the storage.
4. **Delivery and ordering.** `AT_LEAST_ONCE` and `PER_KEY` are the defaults and are nearly always
   right. Both `GLOBAL` ordering and `INFINITE` retention need justification.

Declare all of it in `@EventContract` on the event record. `EventContractRules` rejects combinations
that cannot hold, so a mistake here is a red build rather than a data-loss incident.

## Traps

**Compacting a stream of deltas.** The build catches this one, and it is worth understanding why it
matters: compaction deletes superseded messages, so a consumer replaying from the beginning sees
only the last delta per key and can reconstruct nothing. The damage is invisible until the first
replay, which may be months after the change.

**A key that no longer exists.** Renaming a record component without updating `partitionKey`
degrades to round-robin partitioning and silently drops the per-key ordering guarantee. Checked by
`EventContractRules.partitionKeyExistsOnRecord`.

**Deduplication mistaken for idempotency.** A ledger with a retention window bounds storage; it does
not make a handler correct against a replay older than the window. Where correctness must hold
indefinitely, make the write itself idempotent — an upsert keyed by the business identifier — and
say so in `@Idempotent(note = …)`.

**One consumer group per instance.** Different groups each receive every message. That is how one
email gets sent once per running instance.

## Changing a published contract

Adding an optional field is compatible. Everything else — removing a field, changing a type,
changing the key or the retention — is a new `version` on a new stream. Run both in parallel and
retire the old one when it has no consumers, not when the new one works.

The schema under `contracts/events/` is what consumers in other repositories generate from, so it
changes in the same commit as the record.
