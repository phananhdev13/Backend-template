package com.acme.agentfactory.registry.adapter.out.persistence;

import com.acme.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How an agent is stored, kept apart from {@code AgentDefinition} for the same reason
 * {@code OrderEntity} is kept apart from {@code Order}: this changes for column types and query
 * plans, the aggregate changes for business rules, and a single class serving both would make
 * every schema decision a domain decision.
 */
@Entity
@Table(name = "agent_definition", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class AgentEntity extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "agent_id", nullable = false)
    private List<AgentVersionEntity> versions = new ArrayList<>();

    protected AgentEntity() {
        // Required by JPA.
    }

    AgentEntity(String id, String name, List<AgentVersionEntity> versions) {
        this.id = id;
        this.name = name;
        this.versions = new ArrayList<>(versions);
    }

    /**
     * Replaces this managed entity's state in place, so the update Hibernate issues carries this
     * row's own tracked {@code @Version} rather than the {@code 0} a freshly constructed entity
     * would default to. See {@code JpaAgentRepositoryAdapter.save} for why that distinction is the
     * difference between an update and an optimistic-lock failure on every second save.
     *
     * <p>Each incoming version is matched to the managed child it already has by version number and
     * updated in place rather than replaced outright. Clearing the collection and adding fresh
     * instances would schedule a delete and an insert of rows carrying the same
     * {@code (agent_id, version_number)} - which activation, changing only a version's status,
     * does on every call - and Hibernate flushes inserts before deletes, so the insert collides
     * with the unique constraint the delete has not run yet to clear. Only a version number this
     * agent has never had becomes a genuinely new child row.
     */
    void applyState(String name, List<AgentVersionEntity> versions) {
        this.name = name;
        Map<Integer, AgentVersionEntity> existingByVersionNumber = this.versions.stream()
                .collect(Collectors.toMap(AgentVersionEntity::versionNumber, Function.identity()));
        List<AgentVersionEntity> reconciled = new ArrayList<>();
        for (AgentVersionEntity incoming : versions) {
            AgentVersionEntity existing = existingByVersionNumber.get(incoming.versionNumber());
            if (existing == null) {
                reconciled.add(incoming);
            } else {
                existing.applyState(incoming.status());
                reconciled.add(existing);
            }
        }
        this.versions.clear();
        this.versions.addAll(reconciled);
    }

    String id() {
        return id;
    }

    String name() {
        return name;
    }

    List<AgentVersionEntity> versions() {
        return versions;
    }
}
