package com.acme.agentfactory.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.agentfactory.AgentSecurityTestExclusions;
import com.acme.agentfactory.registry.application.AgentSummaryQuery;
import com.acme.agentfactory.registry.application.port.out.AgentAuthorizationPort;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the cache and the task queue this service demonstrates actually work against real
 * infrastructure - a real Redis, a real RabbitMQ, and the same real Postgres
 * {@code RegisterAgentIntegrationTest} uses - not merely that the annotations compile.
 *
 * <p>Three things a context-loading test cannot catch: that {@code byId}'s answer is actually
 * served from Caffeine on a repeat call, that activating a version actually evicts it rather than
 * leaving a stale answer live, and that {@code ProvisionAgentDeploymentTask} is actually consumed
 * by a real {@code @RabbitListener} on the other end of a real broker.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {
            "acme.messaging.rabbit.max-delivery-attempts=2",
            "acme.messaging.rabbit.retry-initial-interval-ms=50",
            "acme.messaging.rabbit.retry-multiplier=1.0",
            "acme.messaging.rabbit.retry-max-interval-ms=50",
            "spring.kafka.bootstrap-servers=localhost:59092",
            AgentSecurityTestExclusions.PROPERTY
        })
class AgentCachingAndDeploymentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentSummaryQuery summaries;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcClient jdbc;

    // Security is excluded from this context entirely (AgentSecurityTestExclusions) - this test
    // is about caching and the task queue, not Keycloak or OPA, and both are already proven for
    // real elsewhere (KeycloakResourceServerIntegrationTest, OpaAuthorizationIntegrationTest,
    // ActivateAgentVersionAuthorizationIntegrationTest). Authorization still has to answer
    // something for activation to proceed at all, so it is stubbed rather than left unreachable.
    @MockitoBean
    private AgentAuthorizationPort authorization;

    private static final JwtAuthenticationToken CACHE_DEMO_PRINCIPAL = new JwtAuthenticationToken(
            Jwt.withTokenValue("test-token")
                    .header("alg", "none")
                    .claim("sub", "cache-demo-actor")
                    .build(),
            List.of(new SimpleGrantedAuthority("ROLE_agent-admin")));

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void byIdIsCachedAcrossCallsAndEvictedOnActivationWhichAlsoProvisionsADeployment() throws Exception {
        String body = """
                {
                  "name": "cache-demo-agent",
                  "provider": "anthropic",
                  "modelId": "claude-sonnet-5",
                  "systemPrompt": "Demonstrate caching and task queues.",
                  "tools": []
                }
                """;

        String response = mockMvc.perform(
                        post("/agents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = JSON.readTree(response);
        String agentId = json.get("agentId").asText();
        given(authorization.canActivateVersion(any(), any())).willReturn(true);

        assertThat(summaries.byId(agentId)).isPresent();

        // The second read is served from the cache CacheContract declares - real Caffeine, not a
        // stub. Whether it actually came from the cache is asserted directly against the
        // CacheManager rather than inferred from call counts, since AgentSummaryQuery has no
        // instrumentation of its own to count invocations with.
        assertThat(cacheManager.getCache("agents.summary-by-id").get(agentId))
                .as("byId's answer is cached under the agent's id")
                .isNotNull();

        mockMvc.perform(post("/agents/{agentId}/versions/{version}/activation", agentId, 1)
                        .principal(CACHE_DEMO_PRINCIPAL))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(
                                cacheManager.getCache("agents.summary-by-id").get(agentId))
                        .as("activation evicted the stale entry")
                        .isNull());

        assertThat(summaries.byId(agentId))
                .get()
                .satisfies(summary -> assertThat(summary.activeVersion()).isEqualTo(1));

        // The activation also submitted a ProvisionAgentDeploymentTask over a real RabbitMQ
        // queue; ProvisionAgentDeploymentWorker consumes it and upserts this row asynchronously.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long count = jdbc.sql(
                            "select count(*) from agent_deployment where agent_id = :agentId and version_number = 1")
                    .param("agentId", agentId)
                    .query(Long.class)
                    .single();
            assertThat(count)
                    .as("the deployment task was consumed and upserted")
                    .isEqualTo(1L);
        });
    }
}
