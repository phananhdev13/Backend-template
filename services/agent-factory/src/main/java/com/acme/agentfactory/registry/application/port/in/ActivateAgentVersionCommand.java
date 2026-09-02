package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.Command;
import com.acme.kernel.security.Actor;

/** Which version of which agent should become the active one, and who is asking. */
@Command
public record ActivateAgentVersionCommand(String agentId, VersionNumber version, Actor actor) {}
