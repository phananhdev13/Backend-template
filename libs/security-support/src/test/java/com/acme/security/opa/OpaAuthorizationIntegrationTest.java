package com.acme.security.opa;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.kernel.security.Actor;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Proves {@link OpaAuthorization} against a real OPA, not a mocked HTTP response: a real
 * {@code openpolicyagent/opa:1.20.1} container loads {@code acme-authz.rego} from disk, and
 * {@link OpaClient} - Boot's own {@code @ImportHttpServices} proxy, wired exactly the way
 * {@link SecuritySupportAutoConfiguration} wires it for any service that depends on this
 * module - calls it over real HTTP.
 *
 * <p>Confirmed empirically (see {@code OpaClient}'s Javadoc) before writing this test: OPA's
 * REST Data API answers {@code {"result": true|false}}, never anything this test assumed from
 * documentation alone.
 */
@SpringBootTest(
        classes = OpaAuthorizationIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OpaAuthorizationIntegrationTest {

    @Container
    static final GenericContainer<?> OPA = new GenericContainer<>(DockerImageName.parse("openpolicyagent/opa:1.20.1"))
            .withCommand("run", "--server", "--addr", ":8181", "/policy.rego")
            .withCopyFileToContainer(MountableFile.forClasspathResource("opa/acme-authz.rego"), "/policy.rego")
            .withExposedPorts(8181)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void opaProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.http.serviceclient.opa.base-url",
                () -> "http://%s:%d/v1/data/acme/authz/allow".formatted(OPA.getHost(), OPA.getMappedPort(8181)));
    }

    @Autowired
    private OpaAuthorization opaAuthorization;

    private static final Actor ADMIN = new Actor("service-account-x", "svc-x", Set.of("agent-admin"));
    private static final Actor VIEWER = new Actor("service-account-y", "svc-y", Set.of("agent-viewer"));

    @Test
    void aRealOpaDecisionAllowsTheActionForTheGrantedRole() {
        boolean allowed = opaAuthorization.check(ADMIN, "activate-agent-version", Map.of());

        assertThat(allowed).isTrue();
    }

    @Test
    void aRealOpaDecisionDeniesTheActionForAnUngrantedRole() {
        boolean allowed = opaAuthorization.check(VIEWER, "activate-agent-version", Map.of());

        assertThat(allowed).isFalse();
    }

    @Test
    void anUnreachableOpaFailsClosedInsteadOfThrowing() {
        RestClient restClient = RestClient.create("http://127.0.0.1:1");
        OpaClient unreachable = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(OpaClient.class);
        OpaAuthorization authorization = new OpaAuthorization(unreachable);

        boolean allowed = authorization.check(ADMIN, "activate-agent-version", Map.of());

        assertThat(allowed).isFalse();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = com.acme.security.SecuritySupportAutoConfiguration.class)
    static class TestApp {}
}
