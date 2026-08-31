package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import com.acme.agentfactory.registry.domain.ToolName;
import com.acme.kernel.arch.Command;
import java.util.Set;

/** What a caller supplies to propose a new configuration for an existing agent. */
@Command
public record AddAgentVersionCommand(String agentId, ModelRef model, SystemPrompt systemPrompt, Set<ToolName> tools) {

    public AddAgentVersionCommand {
        tools = Set.copyOf(tools);
    }
}
