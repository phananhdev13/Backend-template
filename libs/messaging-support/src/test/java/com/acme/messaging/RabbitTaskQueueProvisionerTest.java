package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.messaging.fixture.FailingTask;
import com.acme.messaging.fixture.SampleTask;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

class RabbitTaskQueueProvisionerTest {

    private final RabbitTaskQueueProvisioner provisioner = new RabbitTaskQueueProvisioner();

    @Test
    void everyTaskGetsItsQueueAndItsDeadLetterQueue() {
        var descriptors = List.of(
                new TaskDescriptor("agents.sample", false, SampleTask.class),
                new TaskDescriptor("agents.failing", false, FailingTask.class));

        var queues = provisioner.toQueues(descriptors).getDeclarablesByType(Queue.class);

        assertThat(queues)
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder(
                        "agents.sample", "agents.sample.dlq", "agents.failing", "agents.failing.dlq");
    }

    @Test
    void everyDeclaredQueueIsDurableAndClassic() {
        var descriptors = List.of(new TaskDescriptor("agents.sample", false, SampleTask.class));

        var queues = provisioner.toQueues(descriptors).getDeclarablesByType(Queue.class);

        assertThat(queues).allSatisfy(queue -> assertThat(queue.isDurable()).isTrue());
        assertThat(queues)
                .allSatisfy(queue -> assertThat(queue.getArguments()).containsEntry("x-queue-type", "classic"));
    }

    @Test
    void noDescriptorsProvisionNoQueues() {
        assertThat(provisioner.toQueues(List.of()).getDeclarables()).isEmpty();
    }
}
