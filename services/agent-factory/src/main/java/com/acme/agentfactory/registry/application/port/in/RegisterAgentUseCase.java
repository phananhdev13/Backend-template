package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.kernel.arch.InputPort;

/**
 * Registering a new agent, with its first draft version.
 *
 * <p>Returns only the new agent's identifier. The version number needs no echo: registration
 * always creates exactly version 1, which is a rule of {@code AgentDefinition.register}, not
 * something the caller has to be told.
 */
@InputPort
public interface RegisterAgentUseCase {

    AgentId registerAgent(RegisterAgentCommand command);
}
