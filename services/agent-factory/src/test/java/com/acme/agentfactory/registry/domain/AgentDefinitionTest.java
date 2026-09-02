package com.acme.agentfactory.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.kernel.error.BusinessRuleViolation;
import com.acme.kernel.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rules, tested where they are cheapest to enumerate: no Spring context, no database.
 */
class AgentDefinitionTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);
    private static final ModelRef MODEL = new ModelRef("anthropic", "claude-sonnet-5");

    private static AgentDefinition registerWithPrompt() {
        return AgentDefinition.register(
                AgentId.newId(),
                new AgentName("support-triage"),
                MODEL,
                new SystemPrompt("You triage support tickets."),
                Set.of(),
                FIXED);
    }

    @Test
    void registeringAnAgentCreatesExactlyOneDraftVersion() {
        AgentDefinition agent = registerWithPrompt();

        assertThat(agent.versions()).hasSize(1);
        assertThat(agent.versions().getFirst().number()).isEqualTo(VersionNumber.first());
        assertThat(agent.versions().getFirst().status()).isEqualTo(AgentVersionStatus.DRAFT);
        assertThat(agent.activeVersion()).isEmpty();
    }

    @Test
    void aVersionWithNoPromptAndNoToolsIsRefused() {
        assertThatThrownBy(() -> AgentDefinition.register(
                        AgentId.newId(), new AgentName("empty-agent"), MODEL, SystemPrompt.none(), Set.of(), FIXED))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("non-blank system prompt or at least one tool");
    }

    @Test
    void aVersionWithOnlyToolsAndNoPromptIsAccepted() {
        AgentDefinition agent = AgentDefinition.register(
                AgentId.newId(),
                new AgentName("tool-only-agent"),
                MODEL,
                SystemPrompt.none(),
                Set.of(new ToolName("web_search")),
                FIXED);

        assertThat(agent.versions()).hasSize(1);
    }

    @Test
    void addingAVersionAssignsTheNextNumberAndLeavesItDraft() {
        AgentDefinition agent = registerWithPrompt();

        AgentVersion second = agent.addVersion(MODEL, new SystemPrompt("Revised prompt."), Set.of(), FIXED);

        assertThat(second.number()).isEqualTo(new VersionNumber(2));
        assertThat(second.status()).isEqualTo(AgentVersionStatus.DRAFT);
        assertThat(agent.versions()).hasSize(2);
    }

    @Test
    void versionNumbersAreNeverReusedEvenAfterMultipleAdditions() {
        AgentDefinition agent = registerWithPrompt();
        agent.addVersion(MODEL, new SystemPrompt("v2"), Set.of(), FIXED);
        agent.addVersion(MODEL, new SystemPrompt("v3"), Set.of(), FIXED);

        assertThat(agent.versions().stream().map(AgentVersion::number))
                .containsExactly(new VersionNumber(1), new VersionNumber(2), new VersionNumber(3));
    }

    @Test
    void activatingAVersionMakesItTheOnlyActiveOne() {
        AgentDefinition agent = registerWithPrompt();

        agent.activateVersion(new VersionNumber(1));

        assertThat(agent.activeVersion()).isPresent();
        assertThat(agent.activeVersion().orElseThrow().number()).isEqualTo(new VersionNumber(1));
    }

    @Test
    void activatingASecondVersionDeprecatesTheFirstRatherThanLeavingTwoActive() {
        AgentDefinition agent = registerWithPrompt();
        agent.addVersion(MODEL, new SystemPrompt("v2"), Set.of(), FIXED);
        agent.activateVersion(new VersionNumber(1));

        agent.activateVersion(new VersionNumber(2));

        assertThat(agent.activeVersion().orElseThrow().number()).isEqualTo(new VersionNumber(2));
        AgentVersion first = agent.versions().stream()
                .filter(v -> v.number().equals(new VersionNumber(1)))
                .findFirst()
                .orElseThrow();
        assertThat(first.status()).isEqualTo(AgentVersionStatus.DEPRECATED);
        // Exactly one active version, never zero or two, at every point in the sequence.
        assertThat(agent.versions().stream()
                        .filter(v -> v.status() == AgentVersionStatus.ACTIVE)
                        .count())
                .isEqualTo(1);
    }

    @Test
    void reactivatingTheAlreadyActiveVersionIsANoOpNotAnError() {
        AgentDefinition agent = registerWithPrompt();
        agent.activateVersion(new VersionNumber(1));

        agent.activateVersion(new VersionNumber(1));

        assertThat(agent.activeVersion().orElseThrow().number()).isEqualTo(new VersionNumber(1));
    }

    @Test
    void activatingAVersionThatDoesNotExistIsRefused() {
        AgentDefinition agent = registerWithPrompt();

        assertThatThrownBy(() -> agent.activateVersion(new VersionNumber(99))).isInstanceOf(NotFoundException.class);
    }
}
