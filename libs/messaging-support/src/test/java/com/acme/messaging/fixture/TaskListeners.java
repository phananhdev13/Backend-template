package com.acme.messaging.fixture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** A plausible, throwaway pair of task handlers, used to prove real publish-and-consume. */
public class TaskListeners {

    private final List<SampleTask> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failingAttempts = new AtomicInteger();

    @RabbitListener(queues = "test.sample-task")
    public void onSampleTask(SampleTask task) {
        received.add(task);
    }

    @RabbitListener(queues = "test.failing-task")
    public void onFailingTask(FailingTask task) {
        failingAttempts.incrementAndGet();
        throw new IllegalStateException("this handler always fails, to prove dead-letter routing");
    }

    public List<SampleTask> received() {
        return received;
    }

    public int failingAttempts() {
        return failingAttempts.get();
    }
}
