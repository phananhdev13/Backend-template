package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;
import java.util.UUID;

/** Identity of an agent definition. */
@ValueObject
public record AgentId(String value) {

    public AgentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An agent id cannot be blank");
        }
    }

    public static AgentId newId() {
        return new AgentId(UUID.randomUUID().toString());
    }
}
