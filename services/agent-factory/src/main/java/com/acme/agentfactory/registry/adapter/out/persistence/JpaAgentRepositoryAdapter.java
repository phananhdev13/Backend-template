package com.acme.agentfactory.registry.adapter.out.persistence;

import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentName;
import com.acme.agentfactory.registry.domain.AgentVersion;
import com.acme.agentfactory.registry.domain.AgentVersionStatus;
import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import com.acme.agentfactory.registry.domain.ToolName;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.OutboundAdapter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Satisfies {@link AgentRepository} with JPA, and owns the translation both ways - the same split
 * of responsibility as {@code JpaOrderRepositoryAdapter}.
 */
@OutboundAdapter(port = AgentRepository.class, kind = AdapterKind.PERSISTENCE)
public class JpaAgentRepositoryAdapter implements AgentRepository {

    private final AgentJpaRepository jpa;

    JpaAgentRepositoryAdapter(AgentJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * Inserts a never-before-seen aggregate, or updates the row already tracking one.
     *
     * <p>An aggregate that already has a row cannot be saved by constructing a fresh detached
     * {@code AgentEntity} and handing it to {@code save()}: a freshly constructed entity's
     * {@code @Version} defaults to {@code 0}, which only ever matches a row's actual version by
     * coincidence. On any activation, capability change or other second save, Hibernate would
     * compare that {@code 0} against whatever the row's version has advanced to, find no match, and
     * report the update as a lost optimistic-lock race that never happened. Loading the managed
     * entity and mutating it in place instead means Hibernate's own dirty checking issues the
     * update against the version it already knows is current.
     */
    @Override
    public void save(AgentDefinition agent) {
        List<AgentVersionEntity> versions = toVersionEntities(agent);
        AgentEntity entity = jpa.findById(agent.id().value())
                .map(existing -> {
                    existing.applyState(agent.name().value(), versions);
                    return existing;
                })
                .orElseGet(
                        () -> new AgentEntity(agent.id().value(), agent.name().value(), versions));
        jpa.save(entity);
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        return jpa.findById(id.value()).map(JpaAgentRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return jpa.existsByName(name);
    }

    private static List<AgentVersionEntity> toVersionEntities(AgentDefinition agent) {
        return agent.versions().stream()
                .map(version -> new AgentVersionEntity(
                        version.number(),
                        version.model().provider(),
                        version.model().modelId(),
                        version.systemPrompt().text(),
                        version.tools().stream().map(ToolName::value).collect(Collectors.toSet()),
                        version.status().name(),
                        version.createdAt()))
                .toList();
    }

    /**
     * Rebuilds the aggregate without re-running the registration or capability rules.
     *
     * <p>Those rules applied when each version was created. Re-applying them on load would make a
     * row that was legal when it was written unreadable later, the first time a rule tightens - the
     * same reasoning as {@code JpaOrderRepositoryAdapter.toDomain}.
     *
     * <p>{@link AgentVersion}'s status is set through reflection here because the constructor that
     * assigns it is package-private by design: nothing outside {@code AgentDefinition} should be
     * able to construct a version in an arbitrary status, and rehydration is the one legitimate
     * exception to that rule, confined to this adapter.
     */
    private static AgentDefinition toDomain(AgentEntity entity) {
        List<AgentVersion> versions = entity.versions().stream()
                .map(JpaAgentRepositoryAdapter::toDomainVersion)
                .toList();
        return AgentDefinition.rehydrate(new AgentId(entity.id()), new AgentName(entity.name()), versions);
    }

    private static AgentVersion toDomainVersion(AgentVersionEntity entity) {
        Set<ToolName> tools = entity.tools().stream().map(ToolName::new).collect(Collectors.toUnmodifiableSet());
        return AgentVersion.rehydrate(
                entity.versionNumber(),
                new ModelRef(entity.provider(), entity.modelId()),
                new SystemPrompt(entity.systemPrompt()),
                tools,
                AgentVersionStatus.valueOf(entity.status()),
                entity.createdAt());
    }
}
