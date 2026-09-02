package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.Command;
import java.time.Instant;

/** Which activation to record, in the domain's own words rather than the event's. */
@Command
public record RecordActivationAuditCommand(AgentId agentId, VersionNumber version, Instant activatedAt) {}
