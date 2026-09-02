package com.acme.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the whole chain against a real Keycloak, not a mocked {@code JwtDecoder}: a real
 * client-credentials token, whose real {@code realm_access.roles} claim becomes a real Spring
 * {@code GrantedAuthority} entirely through {@code authorities-claim-expressions} - no
 * hand-written {@code Converter} anywhere in this test or in {@code SecuritySupportAutoConfiguration}.
 *
 * <p>{@code acme-realm.json} pre-creates a confidential client
 * ({@code security-support-test} / {@code test-secret}) with its service account granted the
 * realm role {@code agent-admin} - a client-credentials grant needs no user, which is the
 * whole reason this test needs no password flow or browser redirect to get a real token.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = KeycloakResourceServerIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class KeycloakResourceServerIntegrationTest {

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    DockerImageName.parse("quay.io/keycloak/keycloak:26.7"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/acme-realm.json"),
                    "/opt/keycloak/data/import/acme-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/acme").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    private static String issuerUri() {
        return "http://%s:%d/realms/acme".formatted(KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    }

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                KeycloakResourceServerIntegrationTest::issuerUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.authority-prefix", () -> "ROLE_");
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions[0]",
                () -> "['realm_access']['roles']");
    }

    @Autowired
    private MockMvc mockMvc;

    private static String accessToken;

    @BeforeAll
    static void fetchARealTokenFromKeycloak() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "grant_type=client_credentials&client_id=security-support-test&client_secret=test-secret";
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Keycloak token endpoint: %s", response.body())
                .isEqualTo(200);
        JsonNode json = JsonMapper.builder().build().readTree(response.body());
        accessToken = json.get("access_token").asText();
    }

    @Test
    void aRealKeycloakTokenWithTheRequiredRoleIsAccepted() throws Exception {
        mockMvc.perform(get("/admin-only").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void noBearerTokenIsRejectedAsAProblemDocument() throws Exception {
        mockMvc.perform(get("/admin-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/auth.unauthenticated"));
    }

    @Test
    void aRealTokenWithoutTheRequiredRoleIsForbiddenAsAProblemDocument() throws Exception {
        mockMvc.perform(get("/viewer-only-nobody-has-this-role").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/auth.forbidden"));
    }

    /**
     * The smallest possible Spring Boot application that pulls in
     * {@link SecuritySupportAutoConfiguration} plus one endpoint per role this test exercises -
     * standing in for a real {@code @UseCase}, since what this test proves is the resource
     * server and method security wiring, not the hexagonal shape a real service would use it in
     * (that is {@code IdempotencyLedgerHousekeepingJob}'s own job, elsewhere).
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import(TestApp.DemoController.class)
    static class TestApp {

        @org.springframework.web.bind.annotation.RestController
        static class DemoController {

            @PreAuthorize("hasRole('agent-admin')")
            @org.springframework.web.bind.annotation.GetMapping("/admin-only")
            String adminOnly() {
                return "ok";
            }

            @PreAuthorize("hasRole('nobody-has-this-role')")
            @org.springframework.web.bind.annotation.GetMapping("/viewer-only-nobody-has-this-role")
            String viewerOnly() {
                return "ok";
            }
        }
    }
}
