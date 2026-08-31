package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.messaging.fixture.BlankKeyEvent;
import com.acme.messaging.fixture.SampleFactEvent;
import com.acme.messaging.fixture.UnannotatedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventContractsTest {

    @Test
    void describeParsesTheDeclaredContract() {
        StreamDescriptor descriptor = EventContracts.describe(SampleFactEvent.class);

        assertThat(descriptor.stream()).isEqualTo("widgets.widget-touched");
        assertThat(descriptor.partitionKey()).isEqualTo("widgetId");
        assertThat(descriptor.retentionDays()).isEqualTo(14);
    }

    @Test
    void anEventWithNoContractCannotBeDescribed() {
        assertThatThrownBy(() -> EventContracts.describe(UnannotatedEvent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no @EventContract");
    }

    @Test
    void partitionKeyValueOfReadsTheDeclaredComponent() {
        SampleFactEvent event = new SampleFactEvent("widget-42", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(EventContracts.partitionKeyValueOf(event)).isEqualTo("widget-42");
    }

    @Test
    void aNullPartitionKeyValueFailsRatherThanPublishingRoundRobin() {
        BlankKeyEvent event = new BlankKeyEvent(null, Instant.now());

        assertThatThrownBy(() -> EventContracts.partitionKeyValueOf(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is null on this instance");
    }

    @Test
    void aBlankPartitionKeyValueFailsRatherThanCollapsingOntoOnePartition() {
        BlankKeyEvent event = new BlankKeyEvent("   ", Instant.now());

        assertThatThrownBy(() -> EventContracts.partitionKeyValueOf(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is blank");
    }
}
