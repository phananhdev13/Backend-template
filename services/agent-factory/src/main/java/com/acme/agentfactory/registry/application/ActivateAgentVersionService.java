package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionUseCase;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.kernel.arch.UseCase;
import com.acme.kernel.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ActivateAgentVersionService(AgentRepository agents, ApplicationEventPublisher events, Clock clock) {
        this.agents = agents;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void activateVersion(ActivateAgentVersionCommand command) {
        AgentId id = new AgentId(command.agentId());
        AgentDefinition agent = agents.findById(id).orElseThrow(() -> NotFoundException.of("Agent", id.value()));
        agent.activateVersion(command.version());
        agents.save(agent);
        events.publishEvent(new AgentVersionActivated(id.value(), command.version(), Instant.now(clock)));
        log.info("UC-AGT-003 activated version agentId={} version={}", id.value(), command.version());
    }
}
