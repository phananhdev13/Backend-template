package com.acme.messaging;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Translates a {@link StreamDescriptor} into the Kafka topic that can keep its promises.
 *
 * <p>This class is the reason the annotation is worth having. Everything a consumer depends on -
 * that history survives, that the latest value per key is never dropped, that messages about one
 * order arrive in order - is enforced by topic configuration, and topic configuration is edited by
 * people who cannot see the Java class that assumed otherwise. Deriving it here means the two
 * cannot drift: recreate the topic from this code and you get the same semantics the code was
 * written against.
 *
 * <p>The mapping below is the whole contract, restated in Kafka's vocabulary:
 *
 * <table>
 *   <caption>Retention modes and their topic configuration</caption>
 *   <tr><th>Retention</th><th>cleanup.policy</th><th>retention.ms</th><th>Also set</th></tr>
 *   <tr><td>TIME_WINDOW</td><td>delete</td><td>window</td><td>-</td></tr>
 *   <tr><td>COMPACTED</td><td>compact</td><td>-1</td>
 *       <td>min.compaction.lag.ms, delete.retention.ms</td></tr>
 *   <tr><td>COMPACTED_AND_WINDOWED</td><td>compact,delete</td><td>window</td>
 *       <td>min.compaction.lag.ms, delete.retention.ms</td></tr>
 *   <tr><td>INFINITE</td><td>delete</td><td>-1</td><td>-</td></tr>
 * </table>
 */
public final class KafkaTopicProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicProvisioner.class);

    /** Kafka's encoding of "never age this out on time". */
    private static final String UNBOUNDED = "-1";

    /** Both compacting policies at once, in the order Kafka's own documentation writes them. */
    private static final String COMPACT_AND_DELETE =
            TopicConfig.CLEANUP_POLICY_COMPACT + "," + TopicConfig.CLEANUP_POLICY_DELETE;

    /**
     * Floor on how long a tombstone stays visible, matching Kafka's own default of 24 hours.
     *
     * <p>A consumer that is offline longer than this window comes back to a log the cleaner has
     * already swept, never sees the delete, and keeps a row for an entity that no longer exists.
     * The deletion is not retried, so the stale row lives until something else overwrites the key -
     * which, for a deleted entity, is never.
     */
    private static final Duration MINIMUM_TOMBSTONE_VISIBILITY = Duration.ofDays(1);

    private final MessagingProperties properties;

    /**
     * @param properties cluster shape; the contract supplies everything else
     */
    public KafkaTopicProvisioner(MessagingProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the topics for a set of contracts.
     *
     * @param descriptors the contracts to provision
     * @return one topic per contract, in the order given
     * @throws UnsupportedContractException if any contract cannot be honoured
     */
    public List<NewTopic> toTopics(Collection<StreamDescriptor> descriptors) {
        List<NewTopic> topics = new ArrayList<>(descriptors.size());
        for (StreamDescriptor descriptor : descriptors) {
            topics.add(toTopic(descriptor));
        }
        return topics;
    }

    /**
     * Builds the topic for one contract.
     *
     * @param descriptor the contract to translate
     * @return a topic whose configuration enforces what the contract promises
     * @throws UnsupportedContractException if the contract asks Kafka to compact a stream of facts
     */
    public NewTopic toTopic(StreamDescriptor descriptor) {
        rejectCompactedFacts(descriptor);
        Map<String, String> configs = new LinkedHashMap<>();
        applyRetention(descriptor, configs);
        applyDurability(descriptor, configs);
        int partitions = partitionsFor(descriptor);
        String name = descriptor.physicalName(properties.getStreamPrefix());
        log.info("Provisioning Kafka topic {} with {} partition(s) and configs {}", name, partitions, configs);
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(properties.getKafka().getReplicationFactor())
                .configs(configs)
                .build();
    }

    /**
     * Partition count, which is the one topic setting the contract dictates outright.
     *
     * <p>Global ordering is a single-partition property and cannot be anything else: Kafka orders
     * within a partition and makes no promise across them, so the only way to guarantee that every
     * message in a stream is observed in publication order is to have exactly one place they can
     * be written. The consequence is that the stream cannot scale past one consumer per group -
     * which is why {@code OrderingGuarantee.GLOBAL} is nearly always a modelling mistake standing
     * in for {@code PER_KEY}, and why silently giving it three partitions would be worse than the
     * throughput ceiling: consumers would get an ordering promise the topic does not keep.
     */
    private int partitionsFor(StreamDescriptor descriptor) {
        if (descriptor.ordering() == OrderingGuarantee.GLOBAL) {
            return 1;
        }
        return properties.getKafka().getPartitions();
    }

    private void applyRetention(StreamDescriptor descriptor, Map<String, String> configs) {
        long windowMs = descriptor.retentionWindow().toMillis();
        switch (descriptor.retention()) {
            case TIME_WINDOW -> {
                configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
                // The window is a recovery budget, not a storage setting: a consumer that falls
                // further behind than this loses the messages it never read, permanently.
                configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(windowMs));
            }
            case COMPACTED -> {
                configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
                // Without this, a compacted topic still deletes whole segments on age, and a key
                // that has not been updated inside the window disappears from a stream whose whole
                // purpose is to hold the current value of every key.
                configs.put(TopicConfig.RETENTION_MS_CONFIG, UNBOUNDED);
                applyCompactionTiming(configs);
            }
            case COMPACTED_AND_WINDOWED -> {
                configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, COMPACT_AND_DELETE);
                // Bounds storage, and gives up the guarantee that a replay from offset zero
                // reconstructs current state: keys untouched for longer than the window are gone.
                // Only correct where the stream is a cache with another source of truth behind it.
                configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(windowMs));
                applyCompactionTiming(configs);
            }
            case INFINITE -> {
                configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
                // Every message forever. Someone owns this disk bill and the replay story; the
                // build makes them say so by requiring an ADR reference on the contract.
                configs.put(TopicConfig.RETENTION_MS_CONFIG, UNBOUNDED);
            }
        }
    }

    private void applyCompactionTiming(Map<String, String> configs) {
        Duration lag = Duration.ofMinutes(properties.getKafka().getMinCompactionLagMinutes());
        // A consumer reading the tail within this window sees every individual update. Past it,
        // the cleaner may already have collapsed several updates for one key into the newest, so a
        // consumer that lags further silently observes fewer states than were published.
        configs.put(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, String.valueOf(lag.toMillis()));
        // Tombstones must outlive the lag that hides them, or a delete can be collected before the
        // consumers it exists to inform are eligible to read it.
        Duration tombstoneVisibility = maxOf(MINIMUM_TOMBSTONE_VISIBILITY, lag.multipliedBy(2));
        configs.put(TopicConfig.DELETE_RETENTION_MS_CONFIG, String.valueOf(tombstoneVisibility.toMillis()));
    }

    private void applyDurability(StreamDescriptor descriptor, Map<String, String> configs) {
        if (descriptor.delivery() != DeliveryGuarantee.EFFECTIVELY_ONCE) {
            return;
        }
        short replicationFactor = properties.getKafka().getReplicationFactor();
        if (replicationFactor < 2) {
            // A single-replica topic cannot require an in-sync quorum, so acks=all is satisfied by
            // the one broker holding the partition and a disk loss is a data loss. Correct for a
            // laptop, never for production - which is why this is loud rather than silent.
            log.warn(
                    "{} declares delivery=EFFECTIVELY_ONCE but acme.messaging.kafka.replication-factor is {}, "
                            + "so min.insync.replicas cannot be raised above 1 and an acknowledged write "
                            + "survives only as long as one broker does. Raise the replication factor in any "
                            + "environment where the guarantee is meant to hold.",
                    descriptor.eventType().getName(),
                    replicationFactor);
            return;
        }
        // Quorum minus one: writes are acknowledged only once a majority holds them, while still
        // tolerating a single broker being down. Setting it equal to the replication factor would
        // make the topic unwritable during any rolling restart.
        configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(replicationFactor - 1));
    }

    private static void rejectCompactedFacts(StreamDescriptor descriptor) {
        if (!descriptor.isCompacted() || descriptor.payload() == PayloadKind.STATE_SNAPSHOT) {
            return;
        }
        throw new UnsupportedContractException(("%s declares retention=%s with payload=FACT, which Kafka will "
                        + "accept and then quietly destroy. Compaction keeps only the newest message per "
                        + "key, so every superseded fact is deleted; a consumer replaying from the "
                        + "beginning rebuilds state from the survivors and gets a different answer than "
                        + "the one that read the stream live. Nothing fails at the time - the loss "
                        + "surfaces at the first replay, months later.%n%n"
                        + "Resolve it one of two ways:%n"
                        + "  1. Publish the entire current state of the entity in each message and set "
                        + "payload=STATE_SNAPSHOT, which is what makes keeping only the latest correct.%n"
                        + "  2. Keep the facts and set retention=TIME_WINDOW (or INFINITE), which keeps "
                        + "the sequence a consumer needs to fold over.")
                .formatted(descriptor.eventType().getName(), descriptor.retention()));
    }

    private static Duration maxOf(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }
}
