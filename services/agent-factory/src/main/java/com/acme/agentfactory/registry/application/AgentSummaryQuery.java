package com.acme.agentfactory.registry.application;

import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.cache.CacheBackend;
import com.acme.kernel.cache.CacheContract;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent summaries, projected straight from the database.
 *
 * <p>Listing agents with their active version is one indexed join; hydrating every
 * {@code AgentDefinition} - every version of every agent - to read one field off each would turn a
 * single query into an N+1. Bypassing the aggregate is safe here for the reason it always is:
 * {@code ReadModelRules.readModelsHaveNoSideEffects} holds this class to changing nothing.
 *
 * <p>Both queries are cached, deliberately on different backends: {@link #byId} is looked up once
 * per request across the platform and evicted the moment an activation changes its answer, which
 * is cheap to keep instance-local. {@link #list} backs a dashboard every instance should agree on
 * even mid-rollout, so it is worth the network hop {@link CacheBackend#DISTRIBUTED} costs.
 */
@ReadModel(id = "QRY-AGT-001")
@Transactional(readOnly = true)
public class AgentSummaryQuery {

    /** What a dashboard needs, and no more. {@code activeVersion} is null until something is activated. */
    public record AgentSummary(String agentId, String name, Integer activeVersion, long versionCount) {}

    private final JdbcClient jdbc;

    public AgentSummaryQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @CacheContract(name = "agents.summary-list", backend = CacheBackend.DISTRIBUTED, ttlSeconds = 30)
    @Cacheable(cacheNames = "agents.summary-list", key = "#limit")
    public List<AgentSummary> list(int limit) {
        return jdbc.sql("""
                        select a.id as agent_id, a.name,
                               (select v.version_number from agent_version v
                                where v.agent_id = a.id and v.status = 'ACTIVE') as active_version,
                               (select count(*) from agent_version v where v.agent_id = a.id) as version_count
                        from agent_definition a
                        order by a.name
                        limit :limit
                        """).param("limit", limit).query(AgentSummary.class).list();
    }

    @CacheContract(name = "agents.summary-by-id", backend = CacheBackend.LOCAL, ttlSeconds = 30)
    @Cacheable(cacheNames = "agents.summary-by-id", key = "#agentId")
    public Optional<AgentSummary> byId(String agentId) {
        return jdbc.sql("""
                        select a.id as agent_id, a.name,
                               (select v.version_number from agent_version v
                                where v.agent_id = a.id and v.status = 'ACTIVE') as active_version,
                               (select count(*) from agent_version v where v.agent_id = a.id) as version_count
                        from agent_definition a
                        where a.id = :agentId
                        """).param("agentId", agentId).query(AgentSummary.class).optional();
    }
}
