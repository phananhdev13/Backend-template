package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;

/**
 * The name a human finds an agent by.
 *
 * <p>Uniqueness is enforced at the repository, not here - a value object can validate its own
 * shape but has no way to see every other agent that exists.
 */
@ValueObject
public record AgentName(String value) {

    private static final int MAX_LENGTH = 200;

    public AgentName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An agent name cannot be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("An agent name cannot exceed " + MAX_LENGTH + " characters");
        }
    }
}
