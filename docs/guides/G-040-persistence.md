# G-040 — Persistence and migrations

The full procedure is in the `persistence` skill. This page is the decisions that are expensive to
reverse.

## The aggregate is not the entity

Two classes, mapped between in the outbound adapter. They change for different reasons — one for
business rules, one for column types and query plans — and fusing them makes every schema decision a
domain decision. It also drags JPA's no-argument constructor and mutable fields into the object
whose whole purpose is to forbid invalid states.

The `@OutputPort` repository interface lives in `application/port/out/` and speaks domain types.
Spring Data interfaces stay inside `adapter/out/persistence/`.

## Transactions

`@Transactional` on the use case, nowhere below. `UseCaseRules.useCasesAreTheTransactionBoundary`
fails anything else, because a boundary opened in an adapter is invisible from the code that decides
what belongs inside it.

Keep remote calls out of transactions: an HTTP call inside one holds a database connection for the
remote system's worst latency, and the pool is smaller than you think.

## Optimistic locking

`AuditableEntity` carries `@Version`. Without it, two concurrent readers both write and the second
silently overwrites the first — a lost update no test catches and no log shows. If a test fails
because of this field, the test has found a real concurrency bug; do not remove the field.

## Migrations

`services/<service>/src/main/resources/db/migration/V<n>__<snake_case>.sql`.

**Expand, migrate, contract** — three deployments, not one, because old and new code run
simultaneously during a rollout:

1. add the new column nullable; write both, read the old
2. backfill; deploy code that reads the new
3. drop the old, once nothing reads it, in a file named `*_contract_*.sql`

`tools/check-migrations.sh` fails a modified migration (Flyway checksums them), a destructive
statement outside a contract step, and a malformed file name.

On PostgreSQL, build indexes `CONCURRENTLY` and add constraints `NOT VALID` then `VALIDATE`: a
plain `ALTER TABLE` takes a lock that queues every query behind it.

## Symptoms

- `LazyInitializationException` — an entity escaped its transaction. Map to the domain object inside
  the adapter; do not open a session in view.
- N+1 — a lazy collection walked in a loop. Fetch-join, or use a `@ReadModel`.
- Deadlocks under load — two use cases updating the same rows in different orders.
