package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import com.acme.messaging.fixture.SampleFactEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamDescriptorTest {

    private static StreamDescriptor descriptor(StreamRetention retention) {
        return new StreamDescriptor(
                "widgets.widget-touched",
                1,
                "widgetId",
                PayloadKind.FACT,
                retention,
                14,
                DeliveryGuarantee.AT_LEAST_ONCE,
                OrderingGuarantee.PER_KEY,
                EventContracts.describe(SampleFactEvent.class).schema(),
                false,
                SampleFactEvent.class);
    }

    @Test
    void physicalNameWithNoPrefixIsJustTheLogicalName() {
        assertThat(descriptor(StreamRetention.TIME_WINDOW).physicalName("")).isEqualTo("widgets.widget-touched.v1");
        assertThat(descriptor(StreamRetention.TIME_WINDOW).physicalName(null)).isEqualTo("widgets.widget-touched.v1");
    }

    @Test
    void physicalNameWithAPrefixIsNamespaced() {
        assertThat(descriptor(StreamRetention.TIME_WINDOW).physicalName("acme"))
                .isEqualTo("acme.widgets.widget-touched.v1");
    }

    @Test
    void aTrailingSeparatorOnThePrefixIsTolerated() {
        assertThat(descriptor(StreamRetention.TIME_WINDOW).physicalName("acme."))
                .isEqualTo("acme.widgets.widget-touched.v1");
    }

    @Test
    void isCompactedIsTrueForBothCompactingModes() {
        assertThat(descriptor(StreamRetention.COMPACTED).isCompacted()).isTrue();
        assertThat(descriptor(StreamRetention.COMPACTED_AND_WINDOWED).isCompacted())
                .isTrue();
        assertThat(descriptor(StreamRetention.TIME_WINDOW).isCompacted()).isFalse();
        assertThat(descriptor(StreamRetention.INFINITE).isCompacted()).isFalse();
    }

    @Test
    void retentionWindowIsTheDeclaredDaysAsADuration() {
        assertThat(descriptor(StreamRetention.TIME_WINDOW).retentionWindow()).isEqualTo(Duration.ofDays(14));
    }
}
