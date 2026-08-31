package com.acme.agentfactory.registry.application.port.in;

import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import com.acme.agentfactory.registry.domain.ToolName;
import com.acme.kernel.arch.Command;
import java.util.Set;

/** What a caller supplies to register a new agent and its first version. */
@Command
public record RegisterAgentCommand(String name, ModelRef model, SystemPrompt systemPrompt, Set<ToolName> tools) {

    public RegisterAgentCommand {
        tools = Set.copyOf(tools);
    }
}
