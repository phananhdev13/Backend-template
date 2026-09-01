package com.acme.agentfactory.registry.domain;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import java.time.Instant;

/**
 * Provision whatever an activated agent version needs to actually serve traffic - a job, not an
 * announcement. Every interested party hears about the activation itself through
 * {@link AgentVersionActivated}; this is the one queue that does the provisioning work, and it is
 * gone the moment a worker finishes it.
 */
@TaskContract(queue = "agents.provision-deployment")
public record ProvisionAgentDeploymentTask(String agentId, int version, Instant submittedAt) implements Task {}
