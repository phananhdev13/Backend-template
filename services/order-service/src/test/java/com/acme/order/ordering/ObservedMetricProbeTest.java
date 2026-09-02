package com.acme.order.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * Proves {@code @Observed} on {@code PlaceOrderService} is not decorative.
 *
 * <p>{@code management.observations.annotations.enabled} is off by default in Boot 4.1 - the
 * annotation compiles and does nothing unless a service turns it on, exactly the trap
 * {@code @WithSpan} sets without the OpenTelemetry agent attached (ADR-0019). Unlike the agent,
 * this property is something {@code mvn test} can and does verify: the assertion below is the
 * regression test for a property line in {@code application.yml} silently going missing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
        properties = {"acme.messaging.auto-provision=false", "spring.kafka.bootstrap-servers=localhost:59092"})
class ObservedMetricProbeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void observedAnnotationProducesATimerNamedForTheUseCase() throws Exception {
        String body = """
                { "customerId": "cust-probe-1", "currency": "EUR",
                  "lines": [ { "sku": "SKU-1", "quantity": 1, "unitPrice": "5.00" } ] }
                """;
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        Timer timer = registry.find("usecase.place-order").timer();
        assertThat(timer)
                .as("no 'usecase.place-order' timer - check management.observations.annotations.enabled "
                        + "in application.yml")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
