package com.acme.temporal.fixture;

import com.acme.kernel.workflow.WorkflowDefinition;
import com.acme.temporal.TemporalActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

@WorkflowDefinition(id = "WF-TEST-001", value = "Composes a greeting through an activity")
public class GreetingWorkflowImpl implements GreetingWorkflow {

    private final GreetingActivities activities =
            Workflow.newActivityStub(GreetingActivities.class, TemporalActivityOptions.of(Duration.ofSeconds(10)));

    @Override
    public String greet(String name) {
        return activities.composeGreeting(name);
    }
}
