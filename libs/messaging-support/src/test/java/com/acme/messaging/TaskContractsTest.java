package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.kernel.task.Task;
import com.acme.messaging.fixture.SampleTask;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskContractsTest {

    @Test
    void parsesTheDeclaredQueue() {
        TaskDescriptor descriptor = TaskContracts.describe(SampleTask.class);

        assertThat(descriptor.queue()).isEqualTo("test.sample-task");
        assertThat(descriptor.containsPersonalData()).isFalse();
        assertThat(descriptor.deadLetterQueue()).isEqualTo("test.sample-task.dlq");
    }

    @Test
    void rejectsATaskTypeWithNoContract() {
        record Undeclared(Instant submittedAt) implements Task {}

        assertThatThrownBy(() -> TaskContracts.describe(Undeclared.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@TaskContract");
    }
}
