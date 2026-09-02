# P-111 — The relational schema states the aggregate's invariants explicitly

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | _review only_ — see **Enforcement** |
| **Annotations** | `@OutboundAdapter`, `@Adr` |
| **Guide** | [persistence](../../.claude/skills/persistence/SKILL.md) |

## Rule

A migration's `CREATE TABLE` is not a dumping ground for whatever columns the entity happens to
have — it is where the aggregate's own invariants get a second, independent enforcement point,
one that holds even when a bug, a script or a future service bypasses the application entirely.
Every foreign-key-shaped column is indexed. Every constraint the domain already enforces in code
— `quantity > 0`, a required field, a bounded range — is also a database `CHECK`, `NOT NULL` or
`FOREIGN KEY` where the column's own table can express it. A table's primary key is the natural
identifier the aggregate already has (an `OrderId`, not a second, meaningless `bigserial` next to
it) unless there is a real reason an aggregate has no stable natural key of its own. `jsonb` is
for data that is genuinely schema-less to this service — an external system's opaque payload, an
extensible attribute bag — never a substitute for columns because adding one felt like more
migration ceremony than the field was worth.

## Why

**A foreign key with no index turns every deletion and cascade check on the referenced table
into a sequential scan of the referencing one.** Postgres does not automatically index a foreign
key column the way some other databases do; `order_line.order_id references orders(id)` with no
index on `order_line.order_id` means deleting one order forces a full scan of `order_line` to
verify nothing still references it, and the same scan runs again for every join a query planner
chooses that path for. This is invisible at development data volumes and a locking incident at
production ones — `order-service`'s own migration indexes `order_line(order_id)` for exactly
this reason.

**A constraint enforced only in application code is a constraint that holds only as long as the
application is the only thing writing to the table.** A backfill script, a support engineer
running a one-off `UPDATE`, a second service added later that shares the schema, a bug in a code
path that skipped validation — none of them go through the aggregate's constructor. `CHECK
(quantity > 0)` on `order_line`, already present in this repository's own schema, catches a
negative quantity no matter what wrote the row; the domain's own validation
([P-021](P-021-illegal-states-unrepresentable.md)) is the fast, friendly rejection for the
common path, and the database constraint is the one that cannot be bypassed.

**A meaningless surrogate key next to the aggregate's real identity is two sources of truth for
"which row is this."** When `orders.id` is a `bigserial` and the domain's `OrderId` is a
separate `uuid` column, every join and every foreign key has to pick one, and the two can drift
— a row found by primary key that does not match the `OrderId` an event referenced is a bug
class that a schema built around the aggregate's own identifier does not have. This repository's
own `orders` table uses `id varchar(64) primary key` holding the domain's own `OrderId` string
for exactly this reason; introduce a separate surrogate only when the aggregate genuinely has no
stable natural identifier of its own (a join table row, an append-only log entry) — not as a
default habit carried over from ORMs that assume one.

**`jsonb` for structured, frequently-queried data trades every advantage a relational schema
gives you for a flexibility the data does not actually need.** A column buried inside a JSON
blob has no `CHECK` constraint, no foreign key, no index without a functional index nobody
remembers to add, and no schema migration path — a typo in a JSON key is a silent data-quality
bug instead of a column that does not exist. Reach for `jsonb` when the shape truly varies per
row and is not this service's business to model (a third-party webhook payload kept for
replay), not as a way to avoid writing a migration for a field the domain already treats as
structured.

## In code

```sql
create table shipment (
    id            varchar(64)              not null primary key,   -- the aggregate's own ShipmentId
    order_id      varchar(64)              not null references orders (id),
    carrier       varchar(32)              not null,
    weight_grams  integer                  not null check (weight_grams > 0),
    dispatched_at timestamp with time zone,
    metadata      jsonb                    not null default '{}'    -- carrier-specific fields this
                                                                     -- service does not model
);

-- Every foreign key gets its own index - Postgres does not create one for you.
create index idx_shipment_order_id on shipment (order_id);
```

Wrong — a surrogate key duplicating the aggregate's real identity, an unindexed foreign key, and
a domain invariant that exists only in Java:

```sql
create table shipment (
    id            bigserial primary key,        -- meaningless: which row is "this" shipment?
    shipment_id   varchar(64) not null,          -- the real identity, now a second column
    order_id      varchar(64) not null,          -- no foreign key, no index: a typo here is silent
    weight_grams  integer                        -- no CHECK: the domain's `> 0` rule is the only one
);
```

## Enforcement

Review only. A migration-file linter can check syntax and destructive statements
([P-110](P-110-expand-migrate-contract.md)'s `tools/check-migrations.sh`), but "is this
constraint actually equivalent to the domain rule it mirrors" and "does this foreign key deserve
an index given how it's queried" need a reader, not a regular expression — a naive "every `_id`
column must appear in an index" check flags legitimate cases (a column already covered by a
composite index in a different column order, a table with no read path that filters on it) as
often as it catches a real gap. Reviewing a migration alongside the entity and the aggregate it
maps is the check that exists today.

## Deviating

A read model or a projection table genuinely does not need the same constraints as the
aggregate's own table — it is rebuilt from the source of truth, not a second place invariants
must hold ([P-032](P-032-reads-and-writes-shaped-separately.md)). A join table with no natural
key of its own (`order_id, tag_id` pairs) is fine with a composite primary key of the two foreign
keys rather than a surrogate — that composite key is the natural identity of the row.

Related: [P-020](P-020-aggregate-consistency-boundaries.md) for where the invariants a schema
mirrors come from; [P-021](P-021-illegal-states-unrepresentable.md) for enforcing them in the
domain first; [P-112](P-112-time-series-hypertables.md) for the schema-design rules specific to
time-series tables.
