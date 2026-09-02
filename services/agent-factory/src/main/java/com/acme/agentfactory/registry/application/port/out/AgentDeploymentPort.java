package com.acme.agentfactory.registry.application.port.out;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.OutputPort;
import java.time.Instant;

/** Records that infrastructure was provisioned for a version of an agent. */
@OutputPort
public interface AgentDeploymentPort {

    /** Idempotent by (agent, version): provisioning the same version twice records it once. */
    void recordProvisioned(AgentId agentId, VersionNumber version, Instant provisionedAt);
}
