package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionUseCase;
import com.acme.agentfactory.registry.application.port.out.AgentAuthorizationPort;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.agentfactory.registry.domain.NotPermitted;
import com.acme.kernel.arch.UseCase;
import com.acme.kernel.cache.CacheBackend;
import com.acme.kernel.cache.CacheContract;
import com.acme.kernel.error.NotFoundException;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes a version to active, deactivating whichever version was active before it - all inside
 * one call on the aggregate, so the two changes commit as one and no reader ever observes an agent
 * with two active versions or a stale one left live.
 */
@UseCase(id = "UC-AGT-003", value = "A platform engineer activates an agent version")
@Transactional
public class ActivateAgentVersionService implements ActivateAgentVersionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ActivateAgentVersionService.class);

    private final AgentRepository agents;
    private final AgentAuthorizationPort authorization;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ActivateAgentVersionService(
            AgentRepository agents,
            AgentAuthorizationPort authorization,
            ApplicationEventPublisher events,
            Clock clock) {
        this.agents = agents;
        this.authorization = authorization;
        this.events = events;
        this.clock = clock;
    }

    // Evicting here rather than from AgentSummaryQuery keeps the invalidation next to the one write
    // that makes byId's cached answer wrong, and is the same cache-name-plus-backend the query
    // declares - CacheContractRules checks the two agree. The eviction advice and the transactional
    // advice are ordered by the container, not declared here; treat this as best-effort staleness
    // reduction on a cache that already expires within ttlSeconds, not a correctness guarantee -
    // that guarantee belongs to the outbox, not to a cache.
    @CacheContract(name = "agents.summary-by-id", backend = CacheBackend.LOCAL, ttlSeconds = 30)
    @CacheEvict(cacheNames = "agents.summary-by-id", key = "#command.agentId()")
    @Override
    @Observed(name = "usecase.activate-agent-version", contextualName = "UC-AGT-003")
    public void activateVersion(ActivateAgentVersionCommand command) {
        AgentId id = new AgentId(command.agentId());
        if (!authorization.canActivateVersion(command.actor(), id)) {
            throw new NotPermitted(
                    "agent.activation-not-permitted",
                    "Actor %s may not activate versions of agent %s"
                            .formatted(command.actor().subject(), id.value()),
                    Map.of("agentId", id.value()));
        }
        AgentDefinition agent = agents.findById(id).orElseThrow(() -> NotFoundException.of("Agent", id.value()));
        agent.activateVersion(command.version());
        agents.save(agent);
        events.publishEvent(
                new AgentVersionActivated(id.value(), command.version().value(), Instant.now(clock)));
        log.info(
                "UC-AGT-003 activated version agentId={} version={}",
                id.value(),
                command.version().value());
    }
}
