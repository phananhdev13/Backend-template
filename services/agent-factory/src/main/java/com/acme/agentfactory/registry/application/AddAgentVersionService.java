package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.AddAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.in.AddAgentVersionUseCase;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentVersion;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.UseCase;
import com.acme.kernel.error.NotFoundException;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Proposes a new configuration for an agent that already exists, as a fresh draft version. */
@UseCase(id = "UC-AGT-002", value = "A platform engineer adds a version to an existing agent")
@Transactional
public class AddAgentVersionService implements AddAgentVersionUseCase {

    private static final Logger log = LoggerFactory.getLogger(AddAgentVersionService.class);

    private final AgentRepository agents;
    private final Clock clock;

    public AddAgentVersionService(AgentRepository agents, Clock clock) {
        this.agents = agents;
        this.clock = clock;
    }

    @Override
    @Observed(name = "usecase.add-agent-version", contextualName = "UC-AGT-002")
    public VersionNumber addAgentVersion(AddAgentVersionCommand command) {
        AgentId id = new AgentId(command.agentId());
        AgentDefinition agent = agents.findById(id).orElseThrow(() -> NotFoundException.of("Agent", id.value()));
        AgentVersion version = agent.addVersion(command.model(), command.systemPrompt(), command.tools(), clock);
        agents.save(agent);
        log.info(
                "UC-AGT-002 added version agentId={} version={}",
                id.value(),
                version.number().value());
        return version.number();
    }
}
