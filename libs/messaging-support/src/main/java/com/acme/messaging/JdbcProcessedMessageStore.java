package com.acme.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The idempotency ledger, as a table.
 *
 * <p>Insert-and-catch rather than read-then-write. The read-then-write version passes every test
 * and fails in production, because two consumers rebalancing onto the same partition both read
 * "not seen" before either writes. The primary key is the only thing that arbitrates that race,
 * so the primary key is what this relies on.
 */
public final class JdbcProcessedMessageStore implements ProcessedMessageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessedMessageStore.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcProcessedMessageStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean markProcessed(String consumerGroup, String messageKey, Duration retention) {
        Instant now = Instant.now(clock);
        try {
            jdbc.sql("insert into processed_message (consumer_group, message_key, processed_at, expires_at) "
                            + "values (:group, :key, :processedAt, :expiresAt)")
                    .param("group", consumerGroup)
                    .param("key", messageKey)
                    .param("processedAt", toBindable(now))
                    .param("expiresAt", toBindable(now.plus(retention)))
                    .update();
            return true;
        } catch (DuplicateKeyException alreadyHandled) {
            log.debug("Delivery {} already handled by group {}", messageKey, consumerGroup);
            return false;
        }
    }

    @Override
    public void purgeExpired() {
        int removed = jdbc.sql("delete from processed_message where expires_at < :now")
                .param("now", toBindable(Instant.now(clock)))
                .update();
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }

    // pgjdbc has no default SQL type for java.time.Instant - only the offset/local JSR-310 types
    // JDBC 4.2 itself defines - so every Instant is bound as the OffsetDateTime that maps directly
    // to timestamptz.
    private static OffsetDateTime toBindable(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
