package com.acme.messaging.fixture;

import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import java.time.Instant;

/** A task whose handler always throws, for tests proving dead-letter routing after retries. */
@TaskContract(queue = "test.failing-task")
public record FailingTask(String jobId, Instant submittedAt) implements Task {}
