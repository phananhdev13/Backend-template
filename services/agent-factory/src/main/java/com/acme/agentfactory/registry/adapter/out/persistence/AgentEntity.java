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
