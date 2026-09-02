package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.AggregateRoot;
import com.acme.kernel.error.BusinessRuleViolation;
import com.acme.kernel.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An agent and every version it has ever run under.
 *
 * <p>Versions are inside the boundary, not separate aggregates, because the one rule that matters -
 * at most one active version - is a statement about all of them together. Splitting versions out
 * would mean activating one requires a transaction across two aggregates to deactivate another,
 * which is exactly the drift {@link com.acme.kernel.arch.AggregateRoot} exists to rule out.
 *
 * <p>There are no setters. {@link #activateVersion} is named for what the business calls it, which
 * is what keeps the "at most one active" invariant from being something a caller could bypass by
 * reaching a version directly.
 */
@AggregateRoot
public final class AgentDefinition {

    private final AgentId id;
    private final AgentName name;
    private final List<AgentVersion> versions;

    private AgentDefinition(AgentId id, AgentName name, List<AgentVersion> versions) {
        this.id = id;
        this.name = name;
        this.versions = new ArrayList<>(versions);
    }

    /**
     * Registers a new agent with its first version, in {@code DRAFT}.
     *
     * <p>There is no way to register an agent already active - activation is always a separate,
     * later decision, so that "this agent is live" is never true the instant it is created.
     */
    public static AgentDefinition register(
            AgentId id, AgentName name, ModelRef model, SystemPrompt systemPrompt, Set<ToolName> tools, Clock clock) {
        AgentVersion first = new AgentVersion(
                VersionNumber.first(), model, systemPrompt, tools, AgentVersionStatus.DRAFT, Instant.now(clock));
        requireCapability(first);
        return new AgentDefinition(id, name, new ArrayList<>(List.of(first)));
    }

    /** Rebuilds an agent from storage without re-running the registration rules. */
    public static AgentDefinition rehydrate(AgentId id, AgentName name, List<AgentVersion> versions) {
        return new AgentDefinition(id, name, versions);
    }

    /**
     * Adds a new draft version, numbered one past the highest number this agent has ever used.
     *
     * <p>Numbers are never reused, even for an abandoned version - a reused number would let two
     * different configurations answer to the same identifier at different points in history.
     */
    public AgentVersion addVersion(ModelRef model, SystemPrompt systemPrompt, Set<ToolName> tools, Clock clock) {
        VersionNumber next = versions.stream()
                .map(AgentVersion::number)
                .max(Comparator.comparingInt(VersionNumber::value))
                .map(VersionNumber::next)
                .orElseGet(VersionNumber::first);
        AgentVersion version =
                new AgentVersion(next, model, systemPrompt, tools, AgentVersionStatus.DRAFT, Instant.now(clock));
        requireCapability(version);
        versions.add(version);
        return version;
    }

    /**
     * Makes one version the active one, deactivating whichever version was active before it.
     *
     * <p>Both changes happen in this one call, which is what keeps the agent from ever being
     * observed with two active versions or with an activation that left a stale one live -
     * something two separate calls, even inside one transaction, could not guarantee against a
     * concurrent reader.
     *
     * <p>Activating the version that is already active changes nothing and is not an error: a
     * caller retrying a request they could not confirm the outcome of should see the same result
     * the first attempt would have given them.
     */
    public void activateVersion(VersionNumber number) {
        AgentVersion target = findVersion(number)
                .orElseThrow(() -> new NotFoundException(
                        "agent-version.not-found",
                        "Agent %s has no version %d".formatted(id.value(), number.value()),
                        Map.of("agentId", id.value(), "version", number.value())));
        if (target.status() == AgentVersionStatus.ACTIVE) {
            return;
        }
        activeVersion().ifPresent(AgentVersion::deprecate);
        target.activate();
    }

    private Optional<AgentVersion> findVersion(VersionNumber number) {
        return versions.stream().filter(v -> v.number().equals(number)).findFirst();
    }

    /** The version currently in effect, if any has ever been activated. */
    public Optional<AgentVersion> activeVersion() {
        return versions.stream()
                .filter(v -> v.status() == AgentVersionStatus.ACTIVE)
                .findFirst();
    }

    public AgentId id() {
        return id;
    }

    public AgentName name() {
        return name;
    }

    public List<AgentVersion> versions() {
        return List.copyOf(versions);
    }

    private static void requireCapability(AgentVersion version) {
        if (!version.hasACapability()) {
            throw new BusinessRuleViolation(
                    "agent-version.no-capability",
                    "A version needs a non-blank system prompt or at least one tool",
                    Map.of("version", version.number().value()));
        }
    }
}
