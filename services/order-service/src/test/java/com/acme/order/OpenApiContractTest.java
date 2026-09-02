package com.acme.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.archtest.RepositoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves this service's checked-in OpenAPI contract - {@code contracts/api/order-service.openapi.json}
 * - is what the controllers actually produce, not a snapshot from whenever someone last remembered
 * to regenerate it.
 *
 * <p>The document is generated at test time, from a real Spring context (springdoc inspects live
 * request mappings; there is no static way to derive it the way {@code contracts/errors/registry.json}
 * is checked, which only ever needed to scan literal exception codes). A hand-maintained OpenAPI
 * file is wrong within a month for the same reason a hand-maintained contract always is - see
 * docs/principles/P-080-api-versioning.md.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime - skipped
 * with a reason rather than failing, and still run in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {"acme.messaging.auto-provision=false", "spring.kafka.bootstrap-servers=localhost:59092"})
class OpenApiContractTest {

    private static final String CONTRACT_PATH = "contracts/api/order-service.openapi.json";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theCheckedInContractMatchesWhatTheControllersActuallyProduce() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        String generated = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(generated)) + "\n";

        Path contractFile = RepositoryLayout.root().resolve(CONTRACT_PATH);
        if (!Files.exists(contractFile)) {
            writeForReview(pretty);
            throw new AssertionError(CONTRACT_PATH + " does not exist yet. A freshly generated copy "
                    + "was written to target/openapi/order-service.openapi.json - review it, then "
                    + "commit it at " + CONTRACT_PATH + ".");
        }

        String checkedIn = Files.readString(contractFile, StandardCharsets.UTF_8);
        if (!checkedIn.equals(pretty)) {
            writeForReview(pretty);
            throw new AssertionError(CONTRACT_PATH + " is stale: the controllers now produce a "
                    + "different contract. A freshly generated copy was written to "
                    + "target/openapi/order-service.openapi.json - review the diff, then replace "
                    + CONTRACT_PATH + " with it.");
        }
        assertThat(checkedIn).isEqualTo(pretty);
    }

    private static void writeForReview(String content) throws IOException {
        Path out = Path.of("target/openapi/order-service.openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, content, StandardCharsets.UTF_8);
    }
}
