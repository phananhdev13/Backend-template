package com.acme.agentfactory.registry.adapter.out.persistence;

import com.acme.agentfactory.registry.application.port.out.ActivationAuditPort;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.OutboundAdapter;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The audit trail is a flat, append-only table, not an aggregate.
 *
 * <p>Plain SQL rather than JPA on purpose: there is no identity to track, no state transition and
 * nothing to load - mapping it as an entity would buy a persistence context for a single insert.
 */
@OutboundAdapter(port = ActivationAuditPort.class, kind = AdapterKind.PERSISTENCE)
public class JdbcActivationAuditAdapter implements ActivationAuditPort {

    private final JdbcClient jdbc;

    public JdbcActivationAuditAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AgentId agentId, VersionNumber version, Instant activatedAt) {
        // pgjdbc has no default SQL type for java.time.Instant - only the offset/local JSR-310
        // types JDBC 4.2 itself defines - so it is bound as the OffsetDateTime that maps directly
        // to timestamptz, not the Instant the event itself carries.
        jdbc.sql("""
                        insert into agent_activation_audit (agent_id, version_number, activated_at)
                        values (:agentId, :version, :activatedAt)
                        """)
                .param("agentId", agentId.value())
                .param("version", version.value())
                .param("activatedAt", activatedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
