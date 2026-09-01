package com.acme.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.acme.temporal.fixture.GreetingActivitiesImpl;
import com.acme.temporal.fixture.GreetingWorkflow;
import com.acme.temporal.fixture.GreetingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves this module's client-construction pattern - {@code WorkflowServiceStubsOptions} with a
 * target, wrapped in a {@code WorkflowClient} - actually connects to and runs a workflow against
 * a real, out-of-process Temporal server, not the in-process test environment
 * {@link GreetingWorkflowTest} uses. There is no official Testcontainers module for Temporal;
 * {@code temporalio/temporal:1.8.2}'s own {@code server start-dev} command is the lightest real
 * server available - single container, in-memory persistence, no separate database to start
 * first the way {@code temporalio/auto-setup} needs.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class TemporalSupportRealServerTest {

    private static final String TASK_QUEUE = "greeting-task-queue";

    @Container
    static final GenericContainer<?> TEMPORAL = new GenericContainer<>(
                    DockerImageName.parse("temporalio/temporal:1.8.2"))
            .withCommand("server", "start-dev", "--ip", "0.0.0.0")
            .withExposedPorts(7233)
            .waitingFor(Wait.forLogMessage(".*Temporal Server:.*\\n", 1));

    private WorkflowServiceStubs stubs;
    private WorkerFactory factory;

    @AfterEach
    void shutdown() {
        if (factory != null) {
            factory.shutdown();
        }
        if (stubs != null) {
            stubs.shutdownNow();
        }
    }

    @Test
    void connectsToARealServerAndRunsAWorkflowThroughARealActivity() {
        String target = TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(7233);
        stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(stubs);

        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new GreetingActivitiesImpl());
        factory.start();

        GreetingWorkflow workflow = client.newWorkflowStub(
                GreetingWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(workflow.greet("Ada")).isEqualTo("Hello, Ada"));
    }
}
