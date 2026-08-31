# P-110 — Schema changes are expand-migrate-contract

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `tools/check-migrations.sh` in CI — migrations are files, which ArchUnit cannot see |
| **Annotations** | `@OutboundAdapter`, `@Adr` |
| **Guide** | [G-040](../guides/G-040-persistence.md) |

## Rule

Every schema change is deployable while the previous version of the application is still
running. Expand the schema, migrate the data and the code, then contract in a later release.
Applied Flyway migrations are immutable; a mistake is fixed by a new migration.

## Why

**Rolling deploys mean two versions of your code run against one database, always.** For the
duration of the rollout — and for however long a rollback window lasts — old pods and new
pods serve traffic simultaneously. A migration that renames `total` to `total_amount` in one
step breaks every old pod the instant it applies, which is the moment before the new pods are
healthy. The result is a full outage during what was supposed to be a zero-downtime deploy,
and the rollback makes it worse: the old version now cannot start against the new schema
either. You are pinned forward, debugging in production, with no way back.

**Contracting immediately also breaks the rollback you are relying on.** Dropping a column in
the same release that stops writing to it means the previous release — the one you would roll
back to — can no longer run. Every deploy is then one-way, which changes how teams behave:
they batch changes to reduce the number of scary deploys, and large batches fail more often.
The three-step dance exists so that rollback stays available.

**Not-null defaults and index builds lock tables.** `ALTER TABLE … ADD COLUMN … NOT NULL`
without a default rewrites the table on older Postgres and takes an `ACCESS EXCLUSIVE` lock;
on a 50-million-row orders table that is minutes of every query blocking behind it, which
presents as a total outage with a healthy-looking database. Add nullable, backfill in
batches, then add the constraint with `NOT VALID` followed by `VALIDATE CONSTRAINT`. Build
indexes `CONCURRENTLY`, outside a transaction.

**Editing an applied migration corrupts the deployment graph.** Flyway records a checksum;
changing a file that has already run makes every environment where it ran fail validation on
next startup, and environments where it has not run get different SQL from the ones where it
has. The two databases are now silently different and nothing will tell you which is right.
The rule is absolute: applied migrations are append-only.

**Backfills must be resumable and bounded.** A single `UPDATE orders SET …` across ten
million rows holds one transaction open for the duration, bloats the WAL, blocks vacuum, and
either times out or takes the replica lag past the point where reads start failing. Batch by
primary key, commit each batch, and make the job restartable from where it stopped — because
it will be interrupted.

## In code

**Release 1 — expand.** Additive only, safe for old and new code:

```sql
-- V12__add_total_currency.sql
ALTER TABLE orders ADD COLUMN total_currency varchar(3);          -- nullable: old code ignores it
CREATE INDEX CONCURRENTLY idx_orders_customer_placed
    ON orders (customer_id, placed_at DESC);                      -- no ACCESS EXCLUSIVE lock
```

The adapter writes both and reads either, so both application versions are correct:

```java
@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
@Adr({"ADR-0022"})     // dual-write window for total_currency, closes in release 3
class JdbcOrderRepository implements OrderRepository {

    @Override
    public Order save(Order order) {
        return jdbc.sql("""
                UPDATE orders
                   SET total_amount = :amount,
                       total_currency = :currency,     -- new
                       currency_code  = :currency      -- old, still written during the window
                 WHERE id = :id AND version = :version
                """) … ;
    }
}
```

**Release 2 — migrate.** Backfill in resumable batches, then constrain without a full lock:

```sql
-- V13__backfill_total_currency.sql
-- Run by the batch job in 10k chunks; this migration only records the constraint.
ALTER TABLE orders
    ADD CONSTRAINT orders_total_currency_not_null
    CHECK (total_currency IS NOT NULL) NOT VALID;      -- no table scan, no lock on existing rows
```

```sql
-- V14__validate_total_currency.sql
ALTER TABLE orders VALIDATE CONSTRAINT orders_total_currency_not_null;   -- SHARE UPDATE EXCLUSIVE
```

**Release 3 — contract.** Only once no running version reads the old column:

```sql
-- V15__drop_currency_code.sql
ALTER TABLE orders DROP COLUMN currency_code;
```

Wrong — an outage and a one-way deploy in two statements:

```sql
-- V12__rename_currency.sql
ALTER TABLE orders RENAME COLUMN currency_code TO total_currency;   -- old pods break instantly
ALTER TABLE orders ALTER COLUMN total_currency SET NOT NULL;        -- full table rewrite under a lock
```

## Enforcement

Migrations are SQL files, so no bytecode rule can see them. `tools/check-migrations.sh` runs in CI
and makes three checks.

**Immutability.** It asks git whether any migration already on the base branch has been modified:

```
MIGRATION  services/order-service/src/main/resources/db/migration/V1__create_orders.sql: modified
after being committed. Flyway checksums applied migrations, so every environment that already ran
this one will refuse to start. Write a new migration.
```

**No destructive statement outside a contract step.** `DROP TABLE`, `DROP COLUMN`, `RENAME COLUMN`
and `SET NOT NULL` fail unless the file is named `*_contract_*.sql`. Old instances are still serving
traffic when a migration lands, so a rename in one step breaks them mid-deploy.

**Naming.** `V<n>__<snake_case_description>.sql`, because Flyway orders by that number and silently
skips a file it cannot parse.

## Deviating

A table that has never been deployed — created in the same release, no rows, no readers — can
be altered freely. Say so in the migration comment; the rule keys off the release tag, so a
same-release change is not flagged.

A genuinely breaking change that cannot be staged — a storage engine migration, a
partitioning change — needs an `@Adr` covering the maintenance window, the rollback plan and
who is awake for it. The point of the principle is that this is a decision with a cost, not a
default.

Related: [P-080](P-080-api-versioning.md) applies the same expand-then-contract shape to the
API surface, and [P-072](P-072-transactional-outbox.md) to event payloads.
