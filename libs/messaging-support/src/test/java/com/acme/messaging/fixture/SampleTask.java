package com.acme.messaging.fixture;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import java.time.Instant;

/** A task that always succeeds, for tests proving a real publish-and-consume round trip. */
@TaskContract(queue = "test.sample-task")
public record SampleTask(String jobId, Instant submittedAt) implements Task {}
