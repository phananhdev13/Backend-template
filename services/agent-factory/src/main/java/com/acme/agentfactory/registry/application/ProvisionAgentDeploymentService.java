package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.ProvisionAgentDeploymentCommand;
import com.acme.agentfactory.registry.application.port.in.ProvisionAgentDeploymentUseCase;
import com.acme.agentfactory.registry.application.port.out.AgentDeploymentPort;
import com.acme.kernel.arch.UseCase;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the deployment provisioned for an activated agent version.
 *
 * <p>The RabbitMQ worker that receives the task is an adapter: it decides nothing and writes
 * nothing. This is where the operation lives, so a second trigger for it - an operator retrying a
 * failed provision, a backfill - reaches the same code, with the same metric and the same log line.
 */
@UseCase(id = "UC-AGT-005", value = "The platform provisions the deployment for an activated version")
public class ProvisionAgentDeploymentService implements ProvisionAgentDeploymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProvisionAgentDeploymentService.class);

    private final AgentDeploymentPort deployments;

    public ProvisionAgentDeploymentService(AgentDeploymentPort deployments) {
        this.deployments = deployments;
    }

    @Override
    @Observed(name = "usecase.provision-agent-deployment", contextualName = "UC-AGT-005")
    public void provisionDeployment(ProvisionAgentDeploymentCommand command) {
        deployments.recordProvisioned(command.agentId(), command.version(), command.submittedAt());
        log.info(
                "UC-AGT-005 provisioned deployment agentId={} version={}",
                command.agentId().value(),
                command.version().value());
    }
}
