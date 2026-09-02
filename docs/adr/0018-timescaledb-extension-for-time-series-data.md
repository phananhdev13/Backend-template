# ADR-0018 — TimescaleDB as a Postgres extension, not a separate time-series database

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-02 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Several capabilities this platform's services are likely to need - deployment health samples, a
device's sensor readings, a metric recorded once a minute for months - share a shape: arriving
continuously, queried mostly by time range, and never updated after they land. Modelled as an
ordinary table, this data gets slower to query every day it runs, in a way invisible in
`EXPLAIN` until it is a production incident (see [P-112](../principles/P-112-time-series-hypertables.md)
for the failure mode in full). Two real approaches were evaluated:

**A separate time-series database** (InfluxDB, ClickHouse) gives purpose-built storage and
query performance for this shape of data, at the cost of a second database this platform's
services would need to run, back up, secure and query with a different client and query
language than everything else here already uses - a second thing for every service that needs
even one time-series table to operate, monitor and reason about failure modes for, alongside
the Postgres this repository's persistence conventions
([libs/persistence-support](../../libs/persistence-support), Flyway, JPA) are already built
around.

**TimescaleDB**, a Postgres extension, turns a table into a *hypertable* - transparently
partitioned into time-range chunks - while every ordinary SQL statement against it, every JPA
entity mapped to it, and every Flyway migration that creates it works exactly as it would
against a plain table. Verified directly against a real server (`timescale/timescaledb:2.29.1-pg17`,
matching this repository's already-pinned `postgres:17-alpine` major version) while building
this platform's own demonstration in `libs/persistence-support`:

- The declarative `CREATE TABLE ... WITH (timescaledb.hypertable, ...)` syntax is TimescaleDB's
  own current recommendation - the older `SELECT create_hypertable(...)` function call is
  labelled legacy in TimescaleDB's own documentation as of this decision. Both still work; this
  platform uses the declarative form.
- A hypertable's primary key, and every unique constraint on it, must include the partitioning
  column - confirmed by the exact failure: `cannot create a unique index without the column
  recorded_at (used in partitioning)`. This is a real JPA-mapping consequence, not a detail that
  stays inside the database: it requires a composite `@EmbeddedId`, never a generated surrogate
  key, on any entity mapped to a hypertable.
- A continuous aggregate must be created `WITH NO DATA` to run inside a Flyway migration, which
  wraps every migration in a transaction - the default (`WITH DATA`) fails with `CREATE
  MATERIALIZED VIEW ... WITH DATA cannot run inside a transaction block`.
- Enabling the columnstore (TimescaleDB's compression) via `ALTER TABLE ... SET
  (timescaledb.enable_columnstore = true, ...)` silently registers its own default columnstore
  policy (compressing chunks after one chunk interval, seven days by default) as a side effect -
  confirmed by `add_columnstore_policy` afterwards failing with "columnstore policy already
  exists," and `if_not_exists => true` only suppressing that error rather than changing the
  interval. Stating a deliberate number requires removing that default policy first.
- Compression, continuous aggregates and retention policies are all available for self-hosting
  under TimescaleDB's own licensing (a mix of Apache 2.0 and the Timescale License, both free to
  self-host; the Timescale License restricts only offering TimescaleDB as a managed database
  service) - no paid tier or enterprise license is required for anything this platform uses.

## Decision

**Time-series data lives in a TimescaleDB hypertable, in the same Postgres this platform's
services already use, never a separate time-series database.**

- `timescale/timescaledb:2.29.1-pg17` (or the equivalent version-pinned tag matching this
  repository's Postgres major version at the time) is the image for both real deployments and
  Testcontainers-based tests of any service with a hypertable - `PostgreSQLContainer` accepts it
  unchanged via `DockerImageName.parse(...).asCompatibleSubstituteFor("postgres")`, since it is a
  real Postgres server plus the extension, not a different wire protocol.
- Every hypertable's primary key includes its partitioning column, mapped in JPA as a composite
  `@EmbeddedId` - never a generated surrogate key racing against TimescaleDB's own per-chunk
  uniqueness requirement.
- A continuous aggregate is created `WITH NO DATA`; its own refresh policy performs the first
  materialization outside the creating migration's transaction.
- Every hypertable states its own retention policy explicitly in its own migration set -
  `add_retention_policy`, or a recorded, deliberate decision not to expire the data
  ([P-112](../principles/P-112-time-series-hypertables.md)) - and `tools/check-migrations.sh`
  fails a hypertable migration that states neither.
- `libs/persistence-support`'s own test suite
  (`TimescaleHypertableIntegrationTest`, `V1`-`V3` in its test migrations) is this decision's
  worked example: a real hypertable, a real continuous aggregate, and real retention and
  columnstore policies, proven against a real server rather than merely against parsed SQL.

## Consequences

**Good** — One database technology, one connection pool, one migration tool, one set of backup
and access-control conventions for every service, whether or not it happens to have time-series
data. A service that later needs to `JOIN` a hypertable against an ordinary table (correlating a
device's readings with its registration record) does so in one query, not across two databases
with no shared transaction.

**Bad** — TimescaleDB is not a fully purpose-built time-series engine the way a system designed
for nothing else is; very high cardinality or very high ingest-rate workloads that outgrow what
a single Postgres instance (even chunked) can absorb would need a different answer this ADR does
not cover. The composite-key requirement is a real JPA-mapping cost every hypertable-backed
entity pays, not hidden by this decision - documented in
[P-112](../principles/P-112-time-series-hypertables.md) rather than a surprise a team discovers
mid-implementation.

**Neutral** — The declarative `WITH (timescaledb.hypertable, ...)` syntax and the columnstore
naming (superseding "compression" as the primary term in TimescaleDB's own current
documentation) are both recent enough that older tutorials and Stack Overflow answers will show
the legacy `create_hypertable()` function call and `add_compression_policy` naming instead - both
still work as of this decision, but the terminology in this platform's own migrations and
principle intentionally matches TimescaleDB's current documentation, not older material found
searching for examples.

## Alternatives considered

### A separate time-series database (InfluxDB, ClickHouse)

Purpose-built for exactly this workload shape, with query languages and storage engines tuned
for it beyond what any Postgres extension reaches. Rejected for the same reason
[ADR-0016](0016-temporal-sdk-direct-not-the-spring-starter.md) and
[ADR-0017](0017-aws-sdk-v2-direct-minio-compatible.md) both favoured depending on a capability
directly over adding a wrapper or a parallel system: every service in this platform already runs
against Postgres, and a second database only earns its operational cost once a real workload
demonstrably outgrows what a chunked, partitioned Postgres table can serve - not by default,
for data volumes this platform's own services are likely to produce for the foreseeable future.

### Hand-rolled Postgres partitioning (native `PARTITION BY RANGE`)

Postgres has had declarative partitioning natively since version 10, with no extension
required. Rejected because TimescaleDB's chunk management, continuous aggregates and retention
policies are exactly the operational machinery a hand-rolled partitioning scheme would otherwise
need built and maintained by hand - creating new partitions on a schedule, materializing rollups,
expiring old ones - solved already, the same reasoning [ADR-0016](0016-temporal-sdk-direct-not-the-spring-starter.md)
used against hand-rolling what Temporal already solves for durable workflows.

## Revisit when

A service's time-series workload demonstrably exceeds what a single, chunked Postgres instance
serves acceptably - measured, not assumed - at which point re-evaluate a purpose-built
time-series or columnar store for that specific service's data, not a platform-wide switch.
Revisit the specific SQL syntax and default-policy behaviour this ADR documents whenever
TimescaleDB's pinned version changes; both the declarative hypertable syntax and the
columnstore-policy defaults are recent enough in TimescaleDB's own history that a future release
could change either again.
