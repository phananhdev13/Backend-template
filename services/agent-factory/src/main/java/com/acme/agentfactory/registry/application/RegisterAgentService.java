package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.RegisterAgentCommand;
import com.acme.agentfactory.registry.application.port.in.RegisterAgentUseCase;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentName;
import com.acme.kernel.arch.UseCase;
import com.acme.kernel.error.ConflictException;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers a new agent with its first draft version.
 *
 * <p>The name-uniqueness check and the save are not atomic against a concurrent registration of
 * the same name - two requests can both pass the check before either commits. A unique constraint
 * on the name column is the real guarantee; this check exists only to answer the common case with
 * a clear {@code agent.name-taken} rather than a database constraint violation reaching the caller
 * as an opaque 500.
 */
@UseCase(id = "UC-AGT-001", value = "A platform engineer registers a new agent")
@Transactional
public class RegisterAgentService implements RegisterAgentUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterAgentService.class);

    private final AgentRepository agents;
    private final Clock clock;

    public RegisterAgentService(AgentRepository agents, Clock clock) {
        this.agents = agents;
        this.clock = clock;
    }

    @Override
    public AgentId registerAgent(RegisterAgentCommand command) {
        if (agents.existsByName(command.name())) {
            throw new ConflictException(
                    "agent.name-taken",
                    "An agent named \"%s\" is already registered".formatted(command.name()),
                    Map.of("name", command.name()));
        }
        AgentDefinition agent = AgentDefinition.register(
                AgentId.newId(),
                new AgentName(command.name()),
                command.model(),
                command.systemPrompt(),
                command.tools(),
                clock);
        agents.save(agent);
        log.info("UC-AGT-001 registered agent agentId={} name={}", agent.id().value(), command.name());
        return agent.id();
    }
}
