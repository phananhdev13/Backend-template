package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import com.acme.messaging.fixture.SampleFactEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

class RabbitStreamProvisionerTest {

    private final MessagingProperties properties = new MessagingProperties();
    private final RabbitStreamProvisioner provisioner = new RabbitStreamProvisioner(properties);

    private static StreamDescriptor descriptor(StreamRetention retention, PayloadKind payload) {
        return new StreamDescriptor(
                "widgets.widget-touched",
                1,
                "widgetId",
                payload,
                retention,
                10,
                DeliveryGuarantee.AT_LEAST_ONCE,
                OrderingGuarantee.PER_KEY,
                EventContracts.describe(SampleFactEvent.class).schema(),
                false,
                SampleFactEvent.class);
    }

    @Test
    void timeWindowSetsMaxAgeInDays() {
        Queue queue = provisioner.toStreamQueue(descriptor(StreamRetention.TIME_WINDOW, PayloadKind.FACT));

        assertThat(queue.getArguments()).containsEntry("x-max-age", "10D");
    }

    @Test
    void infiniteSetsNoMaxAgeArgumentAtAll() {
        Queue queue = provisioner.toStreamQueue(descriptor(StreamRetention.INFINITE, PayloadKind.FACT));

        assertThat(queue.getArguments()).doesNotContainKey("x-max-age");
    }

    @Test
    void compactionIsRefusedRatherThanApproximatedWithMaxAge() {
        StreamDescriptor compacted = descriptor(StreamRetention.COMPACTED, PayloadKind.STATE_SNAPSHOT);

        assertThatThrownBy(() -> provisioner.toStreamQueue(compacted))
                .isInstanceOf(UnsupportedContractException.class)
                .hasMessageContaining("cannot compact")
                .hasMessageContaining("Route this contract to Kafka");
    }

    @Test
    void compactedAndWindowedIsRefusedTooForTheSameReason() {
        StreamDescriptor compacted = descriptor(StreamRetention.COMPACTED_AND_WINDOWED, PayloadKind.STATE_SNAPSHOT);

        assertThatThrownBy(() -> provisioner.toStreamQueue(compacted)).isInstanceOf(UnsupportedContractException.class);
    }

    @Test
    void theQueueIsDurableAndNamedFromTheContract() {
        Queue queue = provisioner.toStreamQueue(descriptor(StreamRetention.TIME_WINDOW, PayloadKind.FACT));

        assertThat(queue.getName()).isEqualTo("widgets.widget-touched.v1");
        assertThat(queue.isDurable()).isTrue();
    }
}
