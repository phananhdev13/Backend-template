package com.acme.agentfactory.registry.adapter.in.messaging;

import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.event.EventHandler;
import com.acme.kernel.event.Idempotent;
import com.acme.messaging.ProcessedMessageStore;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes every activation to an audit log, in its own consumer group.
 *
 * <p>This is the platform's first real message consumer, and exists as much to prove the wiring
 * works as to keep an audit trail: {@code messaging-support} supplies the Kafka serializer, the
 * dead-letter routing and this {@link ProcessedMessageStore} bean, and this class is the one place
 * that puts all three to use in a real {@code @KafkaListener} method. See the {@code events} skill
 * for why {@code @EventHandler} alone - with no listener method - does nothing at all.
 *
 * <p>Consumer group {@code audit-log} is deliberately distinct from whatever group a deployment
 * automation consumer might use: different groups each see every activation, which is exactly what
 * two independent reactions to the same fact require.
 */
@EventHandler(consumes = AgentVersionActivated.class, group = "audit-log")
@Idempotent(key = "agentId", note = "The audit row is an insert keyed by (agentId, version); a repeat is ignored")
@InboundAdapter(AdapterKind.MESSAGING)
public class AgentActivationAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AgentActivationAuditListener.class);

    private static final String CONSUMER_GROUP = "audit-log";

    // Split across two constants so the line fits the repository's 120-column limit; javac folds
    // this back into one compile-time constant, which is all @KafkaListener.topics can accept.
    private static final String ACTIVATED_TOPIC_EXPRESSION = "#{@contractRegistry.byStream("
            + "'agents.agent-version-activated', 1).orElseThrow().physicalName("
            + "'${acme.messaging.stream-prefix:}')}";

    private final ProcessedMessageStore processed;
    private final JdbcClient jdbc;

    public AgentActivationAuditListener(ProcessedMessageStore processed, JdbcClient jdbc) {
        this.processed = processed;
        this.jdbc = jdbc;
    }

    // The topic is resolved from the contract rather than hard-coded, so a stream-prefix or
    // version change needs no edit here - see the events skill's consuming section.
    @KafkaListener(topics = ACTIVATED_TOPIC_EXPRESSION, groupId = CONSUMER_GROUP)
    @Transactional
    public void on(AgentVersionActivated event) {
        // Deduplication key is the delivery, not just the agent: a real audit trail needs one row
        // per activation, so the key folds in the version being activated.
        String deliveryKey = event.agentId() + ":" + event.version();
        boolean firstDelivery = processed.markProcessed(CONSUMER_GROUP, deliveryKey, Duration.ofDays(30));
        if (!firstDelivery) {
            log.debug("Activation already audited agentId={} version={}", event.agentId(), event.version());
            return;
        }
        // pgjdbc has no default SQL type for java.time.Instant - only the offset/local JSR-310
        // types JDBC 4.2 itself defines - so it is bound as the OffsetDateTime that maps directly
        // to timestamptz, not the Instant the event itself carries.
        jdbc.sql("""
                        insert into agent_activation_audit (agent_id, version_number, activated_at)
                        values (:agentId, :version, :activatedAt)
                        """)
                .param("agentId", event.agentId())
                .param("version", event.version())
                .param("activatedAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
        log.info("Audited activation agentId={} version={}", event.agentId(), event.version());
    }
}
