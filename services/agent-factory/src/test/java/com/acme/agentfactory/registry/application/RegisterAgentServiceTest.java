package com.acme.agentfactory.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.agentfactory.registry.application.port.in.RegisterAgentCommand;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import com.acme.kernel.error.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Orchestration, with the port hand-implemented - small enough to write directly, per the
 * {@code testing} skill's preference over configuring a mock for a two-method interface.
 */
class RegisterAgentServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, AgentDefinition> saved = new HashMap<>();
    private final Set<String> names = new HashSet<>();

    private final AgentRepository agents = new AgentRepository() {
        @Override
        public void save(AgentDefinition agent) {
            saved.put(agent.id().value(), agent);
            names.add(agent.name().value());
        }

        @Override
        public Optional<AgentDefinition> findById(AgentId id) {
            return Optional.ofNullable(saved.get(id.value()));
        }

        @Override
        public boolean existsByName(String name) {
            return names.contains(name);
        }
    };

    private final RegisterAgentService service = new RegisterAgentService(agents, FIXED);

    @Test
    void registeringAnAgentSavesItWithOneDraftVersion() {
        AgentId id = service.registerAgent(new RegisterAgentCommand(
                "support-triage",
                new ModelRef("anthropic", "claude-sonnet-5"),
                new SystemPrompt("Triage tickets."),
                Set.of()));

        assertThat(saved).containsKey(id.value());
        assertThat(saved.get(id.value()).versions()).hasSize(1);
    }

    @Test
    void aSecondAgentWithTheSameNameIsRefused() {
        RegisterAgentCommand command = new RegisterAgentCommand(
                "duplicate", new ModelRef("anthropic", "claude-sonnet-5"), new SystemPrompt("First."), Set.of());
        service.registerAgent(command);

        RegisterAgentCommand secondAttempt = new RegisterAgentCommand(
                "duplicate", new ModelRef("openai", "gpt-x"), new SystemPrompt("Second."), Set.of());

        assertThatThrownBy(() -> service.registerAgent(secondAttempt))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");
    }
}
