package com.acme.temporal.fixture;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** A throwaway workflow, real enough that this module's own tests prove a workflow actually
 * runs against a real worker - not merely that the annotations compile. Not a published
 * contract. */
@WorkflowInterface
public interface GreetingWorkflow {

    @WorkflowMethod
    String greet(String name);
}
