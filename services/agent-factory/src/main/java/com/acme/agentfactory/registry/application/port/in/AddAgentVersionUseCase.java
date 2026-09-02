package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.InputPort;

/**
 * Proposing a new configuration for an agent that already exists.
 *
 * <p>Returns the number the aggregate assigned the new version - the one piece of information a
 * caller could not have supplied itself, since numbers are assigned by {@code AgentDefinition}, not
 * chosen by the request.
 */
@InputPort
public interface AddAgentVersionUseCase {

    VersionNumber addAgentVersion(AddAgentVersionCommand command);
}
