package com.acme.agentfactory.registry.application.port.out;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.OutputPort;
import java.time.Instant;

/** Appends to the immutable record of which version of an agent became active, and when. */
@OutputPort
public interface ActivationAuditPort {

    /** Records one activation. Called inside the handler's transaction, never on its own. */
    void append(AgentId agentId, VersionNumber version, Instant activatedAt);
}
