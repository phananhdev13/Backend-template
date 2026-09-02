package com.acme.agentfactory.registry.adapter.out.persistence;

import com.acme.agentfactory.registry.application.port.out.AgentDeploymentPort;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.OutboundAdapter;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The deployment record is a flat table keyed by (agent, version), not an aggregate.
 *
 * <p>{@code on conflict do nothing} is the idempotency the task contract promises, enforced by the
 * database rather than by a deduplication store: a replay that arrives after any retention window
 * has expired still records the deployment exactly once.
 */
@OutboundAdapter(port = AgentDeploymentPort.class, kind = AdapterKind.PERSISTENCE)
public class JdbcAgentDeploymentAdapter implements AgentDeploymentPort {

    private final JdbcClient jdbc;

    public JdbcAgentDeploymentAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordProvisioned(AgentId agentId, VersionNumber version, Instant provisionedAt) {
        // pgjdbc has no default SQL type for java.time.Instant - only the offset/local JSR-310
        // types JDBC 4.2 itself defines - so it is bound as the OffsetDateTime that maps directly
        // to timestamptz, not the Instant the task itself carries.
        jdbc.sql("""
                        insert into agent_deployment (agent_id, version_number, provisioned_at)
                        values (:agentId, :version, :provisionedAt)
                        on conflict (agent_id, version_number) do nothing
                        """)
                .param("agentId", agentId.value())
                .param("version", version.value())
                .param("provisionedAt", provisionedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
