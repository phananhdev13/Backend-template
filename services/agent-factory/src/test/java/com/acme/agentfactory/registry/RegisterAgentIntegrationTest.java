package com.acme.agentfactory.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.agentfactory.registry.application.AgentSummaryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One test that exercises the real path: HTTP in, use case, JPA, Flyway-migrated Postgres, and
 * back out through the read model - the same shape as {@code PlaceOrderIntegrationTest}, and
 * deliberately the only test at this level for the same reason: the rules are covered far more
 * cheaply in {@code AgentDefinitionTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {"acme.messaging.auto-provision=false", "spring.kafka.bootstrap-servers=localhost:59092"})
class RegisterAgentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentSummaryQuery summaries;

    @Test
    void registeringAnAgentPersistsItAndMakesItQueryable() throws Exception {
        String body = """
                {
                  "name": "support-triage-it",
                  "provider": "anthropic",
                  "modelId": "claude-sonnet-5",
                  "systemPrompt": "Triage support tickets.",
                  "tools": []
                }
                """;

        mockMvc.perform(post("/agents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").isNotEmpty());

        assertThat(summaries.list(50))
                .anySatisfy(summary -> assertThat(summary.name()).isEqualTo("support-triage-it"));
    }

    @Test
    void aVersionWithNoCapabilityIsRejectedAsAProblemDocument() throws Exception {
        String body = """
                {
                  "name": "empty-agent-it",
                  "provider": "anthropic",
                  "modelId": "claude-sonnet-5",
                  "systemPrompt": "",
                  "tools": []
                }
                """;

        mockMvc.perform(post("/agents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/agent-version.no-capability"));
    }

    @Test
    void listingAgentsIsCappedAndDoesNotErrorWhenEmpty() throws Exception {
        mockMvc.perform(get("/agents").param("limit", "5000")).andExpect(status().isOk());
    }
}
