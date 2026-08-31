package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;

/**
 * The instructions an agent runs with.
 *
 * <p>Blank is a legitimate value - a version with no prompt but at least one tool is a valid,
 * tool-only agent, and {@link AgentDefinition} is what enforces that at least one of the two is
 * present. A prompt on its own only has to not be null.
 */
@ValueObject
public record SystemPrompt(String text) {

    private static final int MAX_LENGTH = 20_000;

    public SystemPrompt {
        if (text == null) {
            throw new IllegalArgumentException("A system prompt cannot be null; use an empty string for none");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A system prompt cannot exceed " + MAX_LENGTH + " characters");
        }
    }

    public boolean isBlank() {
        return text.isBlank();
    }

    public static SystemPrompt none() {
        return new SystemPrompt("");
    }
}
