package com.acme.agentfactory.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.out.AgentRepository;
import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.AgentName;
import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Proves activation both persists the change and announces it - the two things UC-AGT-003 promises. */
class ActivateAgentVersionServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, AgentDefinition> saved = new HashMap<>();
    private final List<Object> published = new ArrayList<>();

    private final AgentRepository agents = new AgentRepository() {
        @Override
        public void save(AgentDefinition agent) {
            saved.put(agent.id().value(), agent);
        }

        @Override
        public Optional<AgentDefinition> findById(AgentId id) {
            return Optional.ofNullable(saved.get(id.value()));
        }

        @Override
        public boolean existsByName(String name) {
            return saved.values().stream().anyMatch(a -> a.name().value().equals(name));
        }
    };

    private final ApplicationEventPublisher events = published::add;
    private final ActivateAgentVersionService service = new ActivateAgentVersionService(agents, events, FIXED);

    @Test
    void activatingAVersionPersistsItAndAnnouncesIt() {
        AgentDefinition agent = AgentDefinition.register(
                AgentId.newId(),
                new AgentName("support-triage"),
                new ModelRef("anthropic", "claude-sonnet-5"),
                new SystemPrompt("Triage tickets."),
                Set.of(),
                FIXED);
        agents.save(agent);

        service.activateVersion(new ActivateAgentVersionCommand(agent.id().value(), 1));

        assertThat(saved.get(agent.id().value()).activeVersion()).isPresent();
        assertThat(published).hasSize(1);
        AgentVersionActivated event = (AgentVersionActivated) published.getFirst();
        assertThat(event.agentId()).isEqualTo(agent.id().value());
        assertThat(event.version()).isEqualTo(1);
    }
}
