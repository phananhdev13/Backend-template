---
name: persistence
description: Persistence work in this repo - JPA entity vs aggregate mapping, transaction boundaries, optimistic locking, Flyway migration conventions and expand-migrate-contract schema changes. Use when adding or changing a repository, an entity, a query or a database migration, or when diagnosing a lost update, an N+1 or a LazyInitializationException.
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

## Symptoms and causes

- `LazyInitializationException` — an entity escaped its transaction. Map to the domain object
  inside the adapter; do not open a session in view.
- N+1 — a lazy collection walked in a loop. Fetch-join in the query, or use a read model.
- Deadlocks under load — two use cases updating the same rows in different orders. Order writes
  consistently, or narrow the aggregate.
