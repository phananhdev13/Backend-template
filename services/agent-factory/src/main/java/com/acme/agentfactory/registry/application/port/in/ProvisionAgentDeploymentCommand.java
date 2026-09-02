package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.Command;
import java.time.Instant;

/** Which deployment to provision, in the domain's own words rather than the task message's. */
@Command
public record ProvisionAgentDeploymentCommand(AgentId agentId, VersionNumber version, Instant submittedAt) {}
