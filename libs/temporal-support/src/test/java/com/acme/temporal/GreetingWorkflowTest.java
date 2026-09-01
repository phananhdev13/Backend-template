package com.acme.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.temporal.fixture.GreetingActivitiesImpl;
import com.acme.temporal.fixture.GreetingWorkflow;
import com.acme.temporal.fixture.GreetingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves a real {@code @WorkflowDefinition} actually runs against a real worker and calls
 * through to a real activity implementation - not merely that the annotations compile.
 *
 * <p>{@link TestWorkflowEnvironment} is Temporal's own official test harness: an in-process
 * server with time-skipping, so a workflow that sleeps for a day runs in milliseconds. This is
 * the tool Temporal itself recommends for testing workflow logic; a real out-of-process server
 * is exercised separately in {@code TemporalSupportRealServerTest}, which proves the
 * autoconfiguration's connection and worker-registration wiring instead.
 */
class GreetingWorkflowTest {

    private static final String TASK_QUEUE = "greeting-task-queue";

    private TestWorkflowEnvironment testEnvironment;
    private WorkflowClient client;

    @BeforeEach
    void startWorker() {
        testEnvironment = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnvironment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new GreetingActivitiesImpl());
        testEnvironment.start();
        client = testEnvironment.getWorkflowClient();
    }

    @AfterEach
    void shutdown() {
        testEnvironment.close();
    }

    @Test
    void workflowCallsThroughToTheActivityAndReturnsItsResult() {
        GreetingWorkflow workflow = client.newWorkflowStub(
                GreetingWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

        String greeting = workflow.greet("Ada");

        assertThat(greeting).isEqualTo("Hello, Ada");
    }
}
