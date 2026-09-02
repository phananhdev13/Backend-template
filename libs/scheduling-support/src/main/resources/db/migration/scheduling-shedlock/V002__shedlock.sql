-- ShedLock's own lock table. A service that uses @SchedulerLock adds this path to
-- spring.flyway.locations, reserving V002 in the combined sequence for it:
--   spring.flyway.locations=classpath:db/migration/{service},classpath:db/migration/scheduling-shedlock
--
-- lock_until is the safety ceiling, not a heartbeat: a node holding the lock releases it
-- explicitly on success, and every other node treats the row as held until lock_until passes
-- regardless of whether the holder is still alive. locked_at and locked_by are diagnostic only -
-- "which node had this, and since when" - never read by the locking logic itself.
create table shedlock (
    name       varchar(64)              not null,
    lock_until timestamp with time zone not null,
    locked_at  timestamp with time zone not null,
    locked_by  varchar(255)             not null,
    primary key (name)
);
