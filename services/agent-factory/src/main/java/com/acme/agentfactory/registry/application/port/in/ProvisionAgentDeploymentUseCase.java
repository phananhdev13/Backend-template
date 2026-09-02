package com.acme.agentfactory.registry.application.port.in;

import com.acme.kernel.arch.InputPort;

/** Provisions the deployment that serves an activated agent version. */
@InputPort
public interface ProvisionAgentDeploymentUseCase {

    void provisionDeployment(ProvisionAgentDeploymentCommand command);
}
