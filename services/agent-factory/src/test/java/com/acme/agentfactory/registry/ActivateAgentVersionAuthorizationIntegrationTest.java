package com.acme.agentfactory.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.agentfactory.registry.application.AgentSummaryQuery;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves UC-AGT-003's activation endpoint end to end, against real infrastructure at every
 * hop: a real Keycloak issues the bearer token, {@code authorities-claim-expressions} turns its
 * {@code realm_access.roles} into a real Spring {@code GrantedAuthority}, {@code Actors.from}
 * turns that into the {@link com.acme.kernel.security.Actor} the use case reads, and a real
 * OPA - loaded with {@code agent-factory-authz.rego}, the same decision
 * {@code OpaAuthorizationIntegrationTest} in {@code security-support} already proves in
 * isolation - denies or allows the actual activation.
 *
 * <p>Nothing here is mocked: {@code KeycloakResourceServerIntegrationTest} and
 * {@code OpaAuthorizationIntegrationTest} already prove each piece alone; this is the one test
 * proving they compose inside a real {@code @UseCase}, through a real HTTP call.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {
            "acme.messaging.auto-provision=false",
            "spring.kafka.bootstrap-servers=localhost:59092",
            "spring.rabbitmq.listener.simple.missing-queues-fatal=false"
        })
class ActivateAgentVersionAuthorizationIntegrationTest {

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

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    DockerImageName.parse("quay.io/keycloak/keycloak:26.7"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            // Keycloak 26.7 requires the imported file's name to be "<realm>-realm.json" -
            // confirmed the hard way: "agent-factory-realm.json, contains realm acme. File
            // name should be acme-realm.json" refused to import at all.
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/acme-realm.json"),
                    "/opt/keycloak/data/import/acme-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/acme").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> OPA = new GenericContainer<>(DockerImageName.parse("openpolicyagent/opa:1.20.1"))
            .withCommand("run", "--server", "--addr", ":8181", "/policy.rego")
            .withCopyFileToContainer(MountableFile.forClasspathResource("opa/agent-factory-authz.rego"), "/policy.rego")
            .withExposedPorts(8181)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));

    private static String issuerUri() {
        return "http://%s:%d/realms/acme".formatted(KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                ActivateAgentVersionAuthorizationIntegrationTest::issuerUri);
        registry.add(
                "spring.http.serviceclient.opa.base-url",
                () -> "http://%s:%d/v1/data/acme/authz/allow".formatted(OPA.getHost(), OPA.getMappedPort(8181)));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentSummaryQuery summaries;

    private static String tokenFor(String clientId, String secret) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "grant_type=client_credentials&client_id=%s&client_secret=%s".formatted(clientId, secret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Keycloak token endpoint: %s", response.body())
                .isEqualTo(200);
        JsonNode json = JsonMapper.builder().build().readTree(response.body());
        return json.get("access_token").asText();
    }

    @Test
    void anAgentAdminTokenActivatesAVersionThatOpaAllows() throws Exception {
        String adminToken = tokenFor("agent-factory-admin", "admin-secret");
        String agentId = registerAgent(adminToken, "authz-demo-allowed");

        mockMvc.perform(post("/agents/{agentId}/versions/{version}/activation", agentId, 1)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(summaries.byId(agentId))
                .get()
                .satisfies(summary -> assertThat(summary.activeVersion()).isEqualTo(1));
    }

    @Test
    void anAgentViewerTokenIsAuthenticatedButOpaDeniesActivation() throws Exception {
        String adminToken = tokenFor("agent-factory-admin", "admin-secret");
        String viewerToken = tokenFor("agent-factory-viewer", "viewer-secret");
        String agentId = registerAgent(adminToken, "authz-demo-denied");

        mockMvc.perform(post("/agents/{agentId}/versions/{version}/activation", agentId, 1)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/agent.activation-not-permitted"));

        assertThat(summaries.byId(agentId))
                .get()
                .satisfies(summary -> assertThat(summary.activeVersion()).isNull());
    }

    @Test
    void noBearerTokenIsRejectedBeforeReachingTheUseCase() throws Exception {
        String adminToken = tokenFor("agent-factory-admin", "admin-secret");
        String agentId = registerAgent(adminToken, "authz-demo-unauthenticated");

        mockMvc.perform(post("/agents/{agentId}/versions/{version}/activation", agentId, 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/auth.unauthenticated"));
    }

    private String registerAgent(String bearerToken, String name) throws Exception {
        String body = """
                {
                  "name": "%s",
                  "provider": "anthropic",
                  "modelId": "claude-sonnet-5",
                  "systemPrompt": "Demonstrate Keycloak plus OPA.",
                  "tools": []
                }
                """.formatted(name);
        String response = mockMvc.perform(post("/agents")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonMapper.builder().build().readTree(response).get("agentId").asText();
    }
}
