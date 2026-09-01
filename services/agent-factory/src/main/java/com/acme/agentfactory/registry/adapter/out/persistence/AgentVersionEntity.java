package com.acme.agentfactory.registry.adapter.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** A stored agent version. Reached only through {@link AgentEntity}, as in the domain. */
@Entity
@Table(name = "agent_version")
public class AgentVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "agent_version_tool", joinColumns = @JoinColumn(name = "agent_version_id"))
    @Column(name = "tool_name", nullable = false, length = 200)
    private Set<String> tools = new HashSet<>();

    // Stored and read as a plain string; JpaAgentRepositoryAdapter converts to and from
    // AgentVersionStatus at the boundary. @Enumerated is for a Java enum-typed field, which this
    // deliberately is not - see the class Javadoc on why the entity stays framework-simple.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentVersionEntity() {
        // Required by JPA.
    }

    AgentVersionEntity(
            int versionNumber,
            String provider,
            String modelId,
            String systemPrompt,
            Set<String> tools,
            String status,
            Instant createdAt) {
        this.versionNumber = versionNumber;
        this.provider = provider;
        this.modelId = modelId;
        this.systemPrompt = systemPrompt;
        this.tools = new HashSet<>(tools);
        this.status = status;
        this.createdAt = createdAt;
    }

    int versionNumber() {
        return versionNumber;
    }

    String provider() {
        return provider;
    }

    String modelId() {
        return modelId;
    }

    String systemPrompt() {
        return systemPrompt;
    }

    Set<String> tools() {
        return tools;
    }

    String status() {
        return status;
    }

    Instant createdAt() {
        return createdAt;
    }

    /** Updates this managed child's status in place - see {@code AgentEntity.applyState}. */
    void applyState(String status) {
        this.status = status;
    }
}
