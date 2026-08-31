package com.acme.order.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.order.ordering.application.OrderSummaryQuery;
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
 * One test that exercises the real path: HTTP in, use case, JPA, Flyway-migrated Postgres, and back
 * out through the read model.
 *
 * <p>Deliberately the only test at this level. The rules are covered in {@code OrderTest} where each
 * case costs milliseconds; repeating them here would multiply the suite's runtime by the container
 * start-up and prove nothing new. What this covers is what the cheaper levels cannot: that the
 * mapping, the schema and the wiring agree.
 *
 * <p>{@code disabledWithoutDocker} keeps it honest on machines with no container runtime - skipped
 * with a reason rather than failing, and still run in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {
            // Nothing here talks to a broker; the outbox listener is exercised by its own unit test.
            "acme.messaging.auto-provision=false",
            "spring.kafka.bootstrap-servers=localhost:59092"
        })
class PlaceOrderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderSummaryQuery summaries;

    @Test
    void placingAnOrderPersistsItAndMakesItQueryable() throws Exception {
        String body = """
                {
                  "customerId": "cust-integration-1",
                  "currency": "EUR",
                  "lines": [ { "sku": "SKU-1", "quantity": 2, "unitPrice": "10.00" } ]
                }
                """;

        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty());

        assertThat(summaries.forCustomer("cust-integration-1", 10))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.status()).isEqualTo("PLACED");
                    assertThat(summary.subtotal()).isEqualByComparingTo("20.00");
                });
    }

    @Test
    void anEmptyOrderIsRejectedAsAProblemDocument() throws Exception {
        String body = """
                { "customerId": "cust-integration-2", "currency": "EUR", "lines": [] }
                """;

        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://errors.acme.example/validation.failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
