package com.acme.agentfactory.registry.application.port.out;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.kernel.arch.OutputPort;
import com.acme.kernel.security.Actor;

/**
 * Whether an actor may act on a specific agent - decided by a remote policy engine, per
 * P-120's own guidance for that case: kept behind a port so the use case names the question
 * ("may this actor activate this agent's version") without knowing OPA exists.
 */
@OutputPort
public interface AgentAuthorizationPort {

    boolean canActivateVersion(Actor actor, AgentId agentId);
}
