package com.acme.agentfactory.registry.application.port.in;

import com.acme.kernel.arch.InputPort;

/** Promoting a specific version to be the one now in effect. */
@InputPort
public interface ActivateAgentVersionUseCase {

    void activateVersion(ActivateAgentVersionCommand command);
}
