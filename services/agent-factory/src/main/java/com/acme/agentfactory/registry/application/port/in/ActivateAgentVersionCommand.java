package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.Command;

/** Which version of which agent should become the active one. */
@Command
public record ActivateAgentVersionCommand(String agentId, VersionNumber version) {}
