# P-112 — Time-series data lives in a hypertable with a stated retention policy

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `tools/check-migrations.sh` in CI — migrations are files, which ArchUnit cannot see |
| **Annotations** | `@OutboundAdapter`, `@Adr` |
| **Guide** | [persistence](../../.claude/skills/persistence/SKILL.md) |

## Rule

Data that arrives continuously, is queried mostly by time range, and never gets a targeted
`UPDATE` after it lands — a sensor reading, a metric sample, an audit event — goes into a
TimescaleDB hypertable, not a plain table with a `created_at` column and a prayer that nobody
ever runs `SELECT *` over three years of it. Every hypertable states its own retention
explicitly, in the same migration set that creates it: `add_retention_policy`, or a `-- retention:`
comment recording a deliberate decision to keep the data unbounded. A hypertable's primary key
(and every unique constraint on it) includes the partitioning column — this is Postgres and
TimescaleDB's own requirement, not a convention. Rollups a dashboard or a report needs are a
continuous aggregate, refreshed on a schedule, never a query that recomputes `avg()` over the
full history on every page load.

## Why

**An ordinary table storing time-series data is a table that gets slower every day it runs, in
a way that is invisible until it is an incident.** A `sequential scan` over a year of readings
looks identical in `EXPLAIN` to one over a week of them — it is just proportionally slower — so
the query that was fine in staging degrades a little at a time in production until a report
that used to take 200ms takes 20 seconds and nobody can point to the day it broke, because no
single day broke it. TimescaleDB's chunking (automatic partitioning by the time column into
fixed-size ranges) turns "scan everything" into "scan the chunks the query's time range
actually touches" — the same problem [P-110](P-110-expand-migrate-contract.md) solves for
schema changes under load, solved here for query plans under data volume.

**Every unique constraint on a hypertable, primary key included, must contain the partitioning
column — confirmed directly against a real server while building this platform's own
demonstration**: a bare surrogate `id bigserial primary key` on a hypertable fails at
`CREATE TABLE` time with `cannot create a unique index without the column recorded_at (used in
partitioning)`, because TimescaleDB enforces uniqueness per chunk, not across the whole table,
and a constraint that omits the partition column cannot be checked without scanning every chunk.
The fix is a composite key — `primary key (device_id, recorded_at)` — mapped in JPA as one
`@EmbeddedId`, never a generated surrogate. See `SensorReadingId` in `libs/persistence-support`'s
own test suite for the mapping this produces.

**Data with no stated retention grows forever, which is a capacity decision made by default
rather than on purpose.** A sensor table with no retention policy is not "keeping everything
just in case" — it is a disk-usage incident with an unknown arrival date, discovered when a
disk fills up rather than when the decision was made. Stating the number — `interval '90 days'`
— in the same migration that creates the hypertable is this principle's version of
[P-051](P-051-remote-call-resilience.md)'s "state the numbers, defaults are not a policy."

**A continuous aggregate is the time-series shape of reading through a projection instead of
recomputing from source, same as [P-032](P-032-reads-and-writes-shaped-separately.md) already
asks for.** `avg(temperature) over 90 days of raw readings, recomputed on every dashboard
refresh` is exactly the kind of read the write side should never be asked to serve live; a
continuous aggregate materializes the rollup incrementally on its own refresh schedule, so the
dashboard query is a lookup against an already-computed hourly bucket, not an aggregate over
millions of rows.

**A continuous aggregate must be created `WITH NO DATA` inside a Flyway migration — confirmed
directly against a real server.** The default, `WITH DATA`, materializes immediately, which
fails with `CREATE MATERIALIZED VIEW ... WITH DATA cannot run inside a transaction block`,
because Flyway wraps every migration in one. `WITH NO DATA` creates the empty view; its own
refresh policy performs the first materialization afterwards, on schedule, outside the
migration's transaction.

**Enabling the columnstore (TimescaleDB's compression) silently creates its own default policy
— confirmed directly against a real server, not assumed from documentation.** `ALTER TABLE …
SET (timescaledb.enable_columnstore = true, …)` does not merely turn compression on; it also
registers a columnstore policy compressing chunks after one chunk interval (seven days, by
default) as a side effect. Calling `add_columnstore_policy` afterwards to state a different
number fails with `columnstore policy already exists`, and passing `if_not_exists => true` only
suppresses the error — it does not change the interval. Stating a deliberate number requires
`CALL remove_columnstore_policy(...)` first, then `CALL add_columnstore_policy(...)` with the
number actually wanted. This is exactly the kind of default-masquerading-as-absence this
principle exists to force into the open, the same way P-051 forces a stated timeout instead of
an inherited socket default.

## In code

```sql
-- V4__create_device_reading_hypertable.sql
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

```sql
-- V5__create_device_reading_hourly_and_policies.sql
create materialized view device_reading_hourly
with (timescaledb.continuous) as
select device_id,
       time_bucket('1 hour', recorded_at) as bucket,
       avg(value)                         as avg_value
from device_reading
group by device_id, bucket
with no data;                                      -- mandatory inside a Flyway transaction

select add_continuous_aggregate_policy('device_reading_hourly',
    start_offset      => interval '3 hours',
    end_offset        => interval '1 hour',
    schedule_interval  => interval '1 hour');

select add_retention_policy('device_reading', interval '90 days');   -- the stated number
```

Wrong — a plain table, no retention, and a dashboard query that recomputes the rollup every
time:

```sql
create table device_reading (
    id          bigserial primary key,     -- no partitioning column: not viable as a hypertable
    device_id   text        not null,
    recorded_at timestamptz not null,
    value       double precision
);
-- No retention policy: grows forever. No continuous aggregate: every dashboard load
-- recomputes avg(value) across the whole table.
```

## Enforcement

`tools/check-migrations.sh` fails a migration that creates a hypertable
(`create_hypertable(...)` or `timescaledb.hypertable`) when no migration in that service states
`add_retention_policy(...)` or an explicit `-- retention:` comment recording a deliberate
no-expiry decision:

```
MIGRATION  services/telemetry-service/src/main/resources/db/migration/V4__create_device_reading_hypertable.sql:3:
creates a hypertable with no add_retention_policy anywhere in this service's migrations, and no
'-- retention:' comment recording a deliberate decision to keep it unbounded. A hypertable with
no stated retention grows forever by default.
See docs/principles/P-112-time-series-hypertables.md
```

The composite-key requirement and the columnstore-policy default are not independently
mechanised — Postgres itself rejects the former at `CREATE TABLE` time, and the latter is
documented here because it surprised the platform once already.

## Deviating

Genuinely small, slow-growing time-series data — a handful of rows an hour, never expected to
reach millions — does not need hypertable partitioning at all; an ordinary table with a
`created_at` index is fine, and adding TimescaleDB machinery for it is unneeded ceremony, not a
deviation to record.

Data that must be retained indefinitely for a real reason — regulatory, audit-of-record — is a
legitimate `-- retention:` comment explaining why, not a missing policy. State the reason where
the decision is made, the same way an unretried remote call states why in
[P-051](P-051-remote-call-resilience.md).

Related: [P-032](P-032-reads-and-writes-shaped-separately.md) for why a continuous aggregate is
a read model, not a query optimization; [P-110](P-110-expand-migrate-contract.md) for the
migration discipline every hypertable migration still follows;
[ADR-0018](../adr/0018-timescaledb-extension-for-time-series-data.md) for why TimescaleDB as a
Postgres extension, not a separate time-series database.
