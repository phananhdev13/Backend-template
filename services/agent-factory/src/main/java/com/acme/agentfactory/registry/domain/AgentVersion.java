package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.DomainEntity;
import java.time.Instant;
import java.util.Set;

/**
 * One configuration an agent has run, or could run, under.
 *
 * <p>An entity, not a value object: two versions with identical model, prompt and tools are still
 * different versions if they have different numbers, and one version's identity survives the
 * mutation its own {@link #activate()} and {@link #deprecate()} make to its status. Not an
 * aggregate root either - a version cannot be loaded, changed or activated on its own, because
 * activating it changes another version's status in the same operation. It exists only inside
 * {@link AgentDefinition}, which is what makes "at most one active version" possible to guarantee
 * at all, and is reached only through it.
 */
@DomainEntity
public final class AgentVersion {

    private final int number;
    private final ModelRef model;
    private final SystemPrompt systemPrompt;
    private final Set<ToolName> tools;
    private final Instant createdAt;
    private AgentVersionStatus status;

    AgentVersion(
            int number,
            ModelRef model,
            SystemPrompt systemPrompt,
            Set<ToolName> tools,
            AgentVersionStatus status,
            Instant createdAt) {
        this.number = number;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = Set.copyOf(tools);
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * Rebuilds a version from storage in whatever status it was persisted with.
     *
     * <p>The ordinary constructor is package-private so that only {@link AgentDefinition} can bring
     * a version into existence through {@code register} or {@code addVersion} - always as
     * {@code DRAFT}, always through the rules those methods enforce. Rehydration is the one
     * legitimate exception: the status was already decided when the row was written, and re-running
     * the creation rules against it would make a row that was legal then unreadable the first time a
     * rule tightens. This factory exists so the adapter that reads storage - a different package -
     * has a real API for that exception rather than a reason to reach for reflection.
     */
    public static AgentVersion rehydrate(
            int number,
            ModelRef model,
            SystemPrompt systemPrompt,
            Set<ToolName> tools,
            AgentVersionStatus status,
            Instant createdAt) {
        return new AgentVersion(number, model, systemPrompt, tools, status, createdAt);
    }

    void activate() {
        status = AgentVersionStatus.ACTIVE;
    }

    void deprecate() {
        status = AgentVersionStatus.DEPRECATED;
    }

    /** Whether this version can do anything at all once activated. */
    boolean hasACapability() {
        return !systemPrompt.isBlank() || !tools.isEmpty();
    }

    public int number() {
        return number;
    }

    public ModelRef model() {
        return model;
    }

    public SystemPrompt systemPrompt() {
        return systemPrompt;
    }

    public Set<ToolName> tools() {
        return tools;
    }

    public AgentVersionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
