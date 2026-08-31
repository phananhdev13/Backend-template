-- The idempotency ledger backing @Idempotent.
--
-- Services that consume events add this path to spring.flyway.locations:
--   spring.flyway.locations=classpath:db/migration/{service},classpath:db/migration/messaging
--
-- The composite primary key is not an optimisation. It is the only thing that arbitrates two
-- consumers in one group racing onto the same partition after a rebalance, which is what makes
-- markProcessed atomic rather than merely usually correct.
create table processed_message (
    consumer_group varchar(200)             not null,
    message_key    varchar(200)             not null,
    processed_at   timestamp with time zone not null,
    expires_at     timestamp with time zone not null,
    primary key (consumer_group, message_key)
);

-- Housekeeping scans by expiry, never by key.
create index idx_processed_message_expires_at on processed_message (expires_at);
