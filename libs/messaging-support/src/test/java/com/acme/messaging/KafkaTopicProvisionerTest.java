package com.acme.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import com.acme.messaging.fixture.GlobalOrderedEvent;
import com.acme.messaging.fixture.SampleFactEvent;
import com.acme.messaging.fixture.SampleSnapshotEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** One test per row of the retention-mapping table in the class's own Javadoc. */
class KafkaTopicProvisionerTest {

    private final MessagingProperties properties = new MessagingProperties();
    private final KafkaTopicProvisioner provisioner = new KafkaTopicProvisioner(properties);

    private static StreamDescriptor descriptor(
            StreamRetention retention, PayloadKind payload, OrderingGuarantee ordering, DeliveryGuarantee delivery) {
        return new StreamDescriptor(
                "widgets.widget-touched",
                1,
                "widgetId",
                payload,
                retention,
                7,
                delivery,
                ordering,
                EventContracts.describe(SampleFactEvent.class).schema(),
                false,
                SampleFactEvent.class);
    }

    @Test
    void timeWindowDeletesOnAFixedRetention() {
        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.TIME_WINDOW,
                PayloadKind.FACT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.AT_LEAST_ONCE));

        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .containsEntry(
                        TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(java.time.Duration.ofDays(7).toMillis()));
    }

    @Test
    void compactedKeepsEveryMessageAndSetsCompactionTiming() {
        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.COMPACTED,
                PayloadKind.STATE_SNAPSHOT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.AT_LEAST_ONCE));

        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .containsKeys(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, TopicConfig.DELETE_RETENTION_MS_CONFIG);
    }

    @Test
    void compactedAndWindowedCombinesBothPolicies() {
        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.COMPACTED_AND_WINDOWED,
                PayloadKind.STATE_SNAPSHOT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.AT_LEAST_ONCE));

        assertThat(topic.configs().get(TopicConfig.CLEANUP_POLICY_CONFIG))
                .contains("compact")
                .contains("delete");
    }

    @Test
    void infiniteNeverAgesOutButStaysDeleteNotCompact() {
        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.INFINITE,
                PayloadKind.FACT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.AT_LEAST_ONCE));

        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "-1");
    }

    @Test
    void aCompactedStreamOfFactsIsRejected() {
        StreamDescriptor factsCompacted = descriptor(
                StreamRetention.COMPACTED,
                PayloadKind.FACT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.AT_LEAST_ONCE);

        assertThatThrownBy(() -> provisioner.toTopic(factsCompacted))
                .isInstanceOf(UnsupportedContractException.class)
                .hasMessageContaining("quietly destroy");
    }

    @Test
    void globalOrderingForcesExactlyOnePartitionRegardlessOfConfiguration() {
        properties.getKafka().setPartitions(12);

        NewTopic topic = provisioner.toTopic(new StreamDescriptor(
                "widgets.ledger-entry",
                1,
                "widgetId",
                PayloadKind.FACT,
                StreamRetention.INFINITE,
                7,
                DeliveryGuarantee.EFFECTIVELY_ONCE,
                OrderingGuarantee.GLOBAL,
                EventContracts.describe(GlobalOrderedEvent.class).schema(),
                false,
                GlobalOrderedEvent.class));

        assertThat(topic.numPartitions()).isEqualTo(1);
    }

    @Test
    void effectivelyOnceRaisesMinInSyncReplicasWhenReplicationAllowsIt() {
        properties.getKafka().setReplicationFactor((short) 3);

        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.INFINITE,
                PayloadKind.FACT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.EFFECTIVELY_ONCE));

        assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
    }

    @Test
    void effectivelyOnceOnASingleReplicaLeavesMinInSyncReplicasUnset() {
        properties.getKafka().setReplicationFactor((short) 1);

        NewTopic topic = provisioner.toTopic(descriptor(
                StreamRetention.INFINITE,
                PayloadKind.FACT,
                OrderingGuarantee.PER_KEY,
                DeliveryGuarantee.EFFECTIVELY_ONCE));

        assertThat(topic.configs()).doesNotContainKey(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG);
    }

    @Test
    void aSchemaEventDescribesCorrectlyThroughTheProvisioner() {
        StreamDescriptor snapshot = EventContracts.describe(SampleSnapshotEvent.class);

        assertThat(provisioner.toTopic(snapshot).name()).isEqualTo("widgets.widget-state.v1");
    }
}
