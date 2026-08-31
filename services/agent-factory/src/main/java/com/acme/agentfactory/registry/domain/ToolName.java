package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;

/** The name of a capability an agent may call, such as {@code web_search} or {@code run_sql}. */
@ValueObject
public record ToolName(String value) {

    public ToolName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A tool name cannot be blank");
        }
    }
}
