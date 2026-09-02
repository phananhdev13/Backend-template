---
name: persistence
description: Persistence work in this repo - JPA entity vs aggregate mapping, transaction boundaries, optimistic locking, Flyway migration conventions, relational schema/ERD design, TimescaleDB hypertables for time-series data, and expand-migrate-contract schema changes. Use when adding or changing a repository, an entity, a query or a database migration, when designing a table's keys/indexes/constraints, when the data is a time series (a sensor reading, a metric, an audit trail), or when diagnosing a lost update, an N+1 or a LazyInitializationException.
---

# Persistence

## The aggregate is not the entity

Map between them. They change for different reasons - one for business rules, one for column types
and query plans - and fusing them means every schema decision becomes a domain decision.

```
domain/Order.java                        the aggregate: rules, no annotations
adapter/out/persistence/OrderEntity.java @Entity, extends AuditableEntity
adapter/out/persistence/OrderMapper.java between the two
adapter/out/persistence/JpaOrderRepositoryAdapter.java   @OutboundAdapter(port = OrderRepository.class, …)
```

The `@OutputPort` repository interface lives in `application/port/out/` and speaks domain types.
Spring Data interfaces are an implementation detail of the adapter and never leave that package.

## Transactions

`@Transactional` goes on the use case and nowhere below. One use case, one transaction.

A transaction spanning two aggregates is the signal that a boundary is wrong, or that the second
change should be a reaction to an event rather than part of this commit. See
[P-020](../../../docs/principles/P-020-aggregate-consistency-boundaries.md).

Keep remote calls out of transactions. An HTTP call inside one holds a database connection for the
remote system's worst latency, and the connection pool is a much smaller number than you think.

## Optimistic locking is the default

`AuditableEntity` carries `@Version`. Without it, two concurrent readers both write and the second
silently overwrites the first - a lost update that no test catches and no log shows. With it, the
second gets `OptimisticLockingFailureException`, which the edge maps to 409 so the caller can retry
against fresh state.

Do not remove `@Version` to make a test pass. The test is right.

## Migrations

`services/<service>/src/main/resources/db/migration/V<n>__<snake_case_description>.sql`.

Never edit an applied migration - Flyway checksums it and every environment that already ran it
will refuse to start. Write a new one.

A service that depends on `messaging-support` for `ProcessedMessageStore` or the RabbitMQ task
queue shares its classpath with that library's own `V001__processed_message.sql`. Flyway's
`classpath:db/migration` location scans every jar's `db/migration/**` recursively, so that
migration is discovered even if your `spring.flyway.locations` only names your own directory -
and Flyway normalises leading zeros, so **your own `V1` collides with the library's `V001`** with
`Found more than one migration with version 1`. Start a service's own numbering at `V2`.

**Expand, migrate, contract.** A schema change is three deployments, not one, because at any moment
old and new code are both running:

1. **Expand** — add the new column as nullable, or the new table. Deploy code that writes both and
   reads the old.
2. **Migrate** — backfill. Deploy code that reads the new.
3. **Contract** — drop the old, once nothing reads it.

Renaming a column in one step is the single most common cause of a failed deploy here: the old
instances are still running when the migration lands.

Long-running DDL needs care on a live table. On PostgreSQL, create indexes `CONCURRENTLY` and add
constraints `NOT VALID` first, then `VALIDATE` separately - a plain `ALTER TABLE` takes a lock that
queues every query behind it.

## Queries that are not aggregates

A list screen that hydrates aggregates will be slow. Use a `@ReadModel` and project straight from
the database - reads are allowed to bypass the domain precisely because they change nothing, and
`ReadModelRules.readModelsHaveNoSideEffects` holds them to it. See
[P-032](../../../docs/principles/P-032-reads-and-writes-shaped-separately.md).

## Designing the schema: keys, indexes, constraints

The migration is a second, independent enforcement point for the aggregate's invariants, not a
column-for-column transcription of the entity. See
[P-111](../../../docs/principles/P-111-relational-schema-states-invariants.md) for the reasoning.

- **The primary key is the aggregate's own identifier** (`orders.id varchar(64)` holding the
  `OrderId` string), not a meaningless surrogate `bigserial` sitting next to it. Two ids for one
  row is two sources of truth for "which row is this." Reach for a surrogate only when the row
  genuinely has no natural identity of its own (a join table, an append-only log entry).
- **Index every foreign-key-shaped column.** Postgres does not do this for you; an unindexed
  `order_id` on `order_line` turns every delete-cascade check on `orders` into a sequential scan
  of `order_line`. `order-service`'s own migration indexes it for exactly this reason.
- **Mirror the domain's invariants as constraints**, not only as validation in the aggregate's
  constructor: `check (quantity > 0)`, `not null`, a `references` clause. A backfill script, a
  one-off support query or a bug in a code path that skipped validation still has to go through
  the database; the domain's own checks are the fast, friendly rejection, the constraint is the
  one that cannot be bypassed.
- **Reach for `jsonb` only for data this service genuinely does not model** - an external
  system's opaque payload kept for replay, an extensible attribute bag - never as a shortcut past
  writing a migration for a field the domain already treats as structured. A typo inside a JSON
  blob is a silent data-quality bug; a typo in a column name does not compile.

## Time-series data: TimescaleDB hypertables

Data that arrives continuously, is queried mostly by time range, and is never updated after it
lands - a sensor reading, a metric sample, a deployment health check - goes into a TimescaleDB
hypertable. See [P-112](../../../docs/principles/P-112-time-series-hypertables.md) and
[ADR-0018](../../../docs/adr/0018-timescaledb-extension-for-time-series-data.md) for the full
reasoning; this section is the how, including the pitfalls a real server surfaced while this
platform's own demonstration was built.

**Create the hypertable declaratively**, with the primary key including the partitioning column
- TimescaleDB enforces uniqueness per chunk, so a constraint that omits the partition column
cannot be checked without scanning every chunk, and a bare surrogate id fails at `CREATE TABLE`
time:

```sql
create table device_reading (
    device_id   text                     not null,
    recorded_at timestamptz              not null,
    value       double precision,
    primary key (device_id, recorded_at)          -- must include the partition column
) with (
    timescaledb.hypertable,
    timescaledb.partition_column = 'recorded_at'
);
```

**Map the composite key as one `@EmbeddedId`**, never `@IdClass`'s two declarations of the same
fields, and never a generated surrogate:

```java
@Embeddable
public class DeviceReadingId implements Serializable {
    private String deviceId;
    private Instant recordedAt;
    // equals/hashCode over both fields, a protected no-arg constructor for JPA
}

@Entity
@Table(name = "device_reading")
public class DeviceReadingEntity {
    @EmbeddedId
    private DeviceReadingId id;
    // no @Version, no AuditableEntity: a raw reading is insert-only, nothing to race against
}
```

**A continuous aggregate must be created `WITH NO DATA`** - Flyway wraps every migration in a
transaction, and the default (`WITH DATA`) fails with `CREATE MATERIALIZED VIEW ... WITH DATA
cannot run inside a transaction block`. Its own refresh policy performs the first materialization
afterwards, on schedule:

```sql
create materialized view device_reading_hourly
with (timescaledb.continuous) as
select device_id, time_bucket('1 hour', recorded_at) as bucket, avg(value) as avg_value
from device_reading
group by device_id, bucket
with no data;

select add_continuous_aggregate_policy('device_reading_hourly',
    start_offset => interval '3 hours', end_offset => interval '1 hour',
    schedule_interval => interval '1 hour');
```

**State the retention policy explicitly, in the same migration set that creates the
hypertable** - a hypertable with none grows forever by default, and `tools/check-migrations.sh`
fails a hypertable migration that states neither a policy nor a `-- retention:` comment recording
a deliberate decision to keep the data unbounded:

```sql
select add_retention_policy('device_reading', interval '90 days');
```

**Enabling the columnstore (compression) silently creates its own default policy** - confirmed
against a real server: `ALTER TABLE ... SET (timescaledb.enable_columnstore = true, ...)`
registers a columnstore policy compressing chunks after one chunk interval (seven days by
default) as a side effect. Calling `add_columnstore_policy` afterwards to state a different
number fails with `columnstore policy already exists`, and `if_not_exists => true` only
suppresses the error rather than changing the interval - remove the default first:

```sql
alter table device_reading set (
    timescaledb.enable_columnstore = true,
    timescaledb.segmentby          = 'device_id',
    timescaledb.orderby            = 'recorded_at desc'
);
call remove_columnstore_policy('device_reading', if_exists => true);
call add_columnstore_policy('device_reading', after => interval '30 days');
```

**Testing** uses the same `timescale/timescaledb:<version>-pg<major>` image as production,
accepted by `PostgreSQLContainer` via `asCompatibleSubstituteFor("postgres")` since it is a real
Postgres server plus the extension:

```java
@Container
@ServiceConnection
static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(
        DockerImageName.parse("timescale/timescaledb:2.29.1-pg17").asCompatibleSubstituteFor("postgres"));
```

A `@DataJpaTest` wraps each test in a transaction it rolls back at the end, on one held
connection - a continuous aggregate's refresh, like any background-worker policy, only sees
committed data through its own session, so a test proving one actually rolls up data needs
`@Transactional(propagation = Propagation.NOT_SUPPORTED)` to make its own writes visible outside
itself.

For a complete, real example - the composite-key mapping, the continuous aggregate, and both
policies, each proven against a real server rather than merely parsed - read
`libs/persistence-support/src/test/java/com/acme/persistence/TimescaleHypertableIntegrationTest.java`
and its `V1`-`V3` migrations under `src/test/resources/db/migration`.

## Symptoms and causes

- `LazyInitializationException` — an entity escaped its transaction. Map to the domain object
  inside the adapter; do not open a session in view.
- N+1 — a lazy collection walked in a loop. Fetch-join in the query, or use a read model.
- Deadlocks under load — two use cases updating the same rows in different orders. Order writes
  consistently, or narrow the aggregate.
- `ObjectOptimisticLockingFailureException` on a save that should be an ordinary update — the
  adapter built a fresh detached entity and handed it to `save()`. A freshly constructed entity's
  `@Version` defaults to `0`, which only matches a row's actual version by coincidence. Load the
  managed entity with `findById` and mutate it in place instead; see `JpaOrderRepositoryAdapter`
  or `JpaAgentRepositoryAdapter` for the pattern. If the aggregate has a child collection with a
  unique constraint, reconcile children by natural key rather than clearing and re-adding them
  wholesale - Hibernate flushes inserts before deletes, so a wholesale replace collides with the
  constraint on rows that only changed a non-key column (`AgentEntity.applyState`).
- `BadSqlGrammarException: ... Can't infer the SQL type to use for an instance of
  java.time.Instant` from a raw `JdbcClient` statement — pgjdbc has no default SQL type for
  `Instant`; JDBC 4.2 only defines conversions for the offset and local JSR-310 types. Bind
  `instant.atOffset(ZoneOffset.UTC)` instead. `Instant` read back from a `timestamptz` column is
  fine either way - this only bites parameter binding, not result mapping.
