package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;

/**
 * A version's ordinal within its agent - assigned by {@link AgentDefinition}, in order, never
 * reused. A bare {@code int} let {@code activateVersion} and {@code addVersion} be called with any
 * integer at all, including zero or a negative one no aggregate could ever have assigned.
 */
@ValueObject
public record VersionNumber(int value) {

    public VersionNumber {
        if (value < 1) {
            throw new IllegalArgumentException("A version number must be at least 1, got " + value);
        }
    }

    /** The number every agent's first version is assigned. */
    public static VersionNumber first() {
        return new VersionNumber(1);
    }

    /** The next number after this one, for the version that supersedes it. */
    public VersionNumber next() {
        return new VersionNumber(value + 1);
    }
}
