package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRegistryTest {

    @Test
    void discoversEveryTaskContractUnderTheGivenPackages() {
        TaskRegistry registry = new TaskRegistry(List.of("com.acme.messaging.fixture"));

        assertThat(registry.all())
                .extracting(TaskDescriptor::queue)
                .containsExactlyInAnyOrder("test.sample-task", "test.failing-task");
    }

    @Test
    void looksUpADescriptorByQueueName() {
        TaskRegistry registry = new TaskRegistry(List.of("com.acme.messaging.fixture"));

        assertThat(registry.byQueue("test.sample-task")).isPresent();
        assertThat(registry.byQueue("no.such.queue")).isEmpty();
    }

    @Test
    void emptyBasePackagesDiscoverNothing() {
        assertThat(new TaskRegistry(List.of()).all()).isEmpty();
        assertThat(new TaskRegistry(null).all()).isEmpty();
    }
}
