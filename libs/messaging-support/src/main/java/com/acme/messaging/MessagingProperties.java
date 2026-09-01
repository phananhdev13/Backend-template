package com.acme.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deployment-shaped half of messaging configuration.
 *
 * <p>Note what is here and what is not. Nothing in this class can change what a stream *means* -
 * its key, its retention, its ordering or its delivery guarantee all live on the contract, where a
 * consumer can see them. What lives here is what legitimately differs between a laptop and a
 * production cluster: how many partitions the cluster can afford, how many replicas it has, and
 * which prefix keeps this environment's streams out of another's.
 *
 * <p>That split is the point. A property file that could turn compaction off would reintroduce
 * exactly the drift {@code @EventContract} exists to prevent.
 */
@ConfigurationProperties("acme.messaging")
public class MessagingProperties {

    private String streamPrefix = "";

    private List<String> basePackages = new ArrayList<>();

    private boolean autoProvision = true;

    private final Kafka kafka = new Kafka();

    private final Rabbit rabbit = new Rabbit();

    /**
     * Prefix distinguishing this environment's or tenant's streams on a shared broker.
     *
     * <p>Blank by default so local development needs no configuration at all - and so that a
     * missing prefix is visibly a bare topic name rather than something that looks configured.
     *
     * @return the configured prefix, possibly blank
     */
    public String getStreamPrefix() {
        return streamPrefix;
    }

    public void setStreamPrefix(String streamPrefix) {
        this.streamPrefix = streamPrefix;
    }

    /**
     * Packages scanned for {@code @EventContract} types.
     *
     * <p>Empty means the scan is skipped and no streams are provisioned. A service that publishes
     * nothing should not be forced to declare an empty package list, and a service that publishes
     * something will notice immediately that its topics are missing.
     *
     * @return the base packages to scan
     */
    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }

    /**
     * Whether this application creates its own streams.
     *
     * <p>True by default, which is right for development and for teams that own their topics. Set
     * it false where infrastructure creates topics and the application has no admin rights - the
     * contracts are still parsed and still validated, so an impossible declaration fails at
     * startup either way.
     *
     * @return true to declare topics and queues from the discovered contracts
     */
    public boolean isAutoProvision() {
        return autoProvision;
    }

    public void setAutoProvision(boolean autoProvision) {
        this.autoProvision = autoProvision;
    }

    /**
     * Kafka cluster shape.
     *
     * @return the nested Kafka settings
     */
    public Kafka getKafka() {
        return kafka;
    }

    /**
     * RabbitMQ task queue retry policy.
     *
     * @return the nested Rabbit settings
     */
    public Rabbit getRabbit() {
        return rabbit;
    }

    /** Cluster capabilities that no contract can know, and no contract should have to state. */
    public static class Kafka {

        private int partitions = 3;

        private short replicationFactor = 1;

        private int minCompactionLagMinutes = 60;

        private List<String> trustedPackages = new ArrayList<>();

        private int maxDeliveryAttempts = 4;

        private long retryBackoffMs = 1000;

        /**
         * Partitions for streams that do not demand global ordering.
         *
         * <p>The ceiling on consumer parallelism for a group, and effectively permanent: adding
         * partitions later re-hashes keys onto different partitions, so messages for one key can
         * be in flight on two partitions at once and per-key ordering breaks across the change.
         *
         * @return the partition count
         */
        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        /**
         * Replication factor for created topics.
         *
         * <p>Defaults to 1 because that is what a single-broker development cluster can satisfy;
         * a topic asking for more than the cluster has is simply not created. Production values
         * belong in the production profile.
         *
         * @return the replication factor
         */
        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }

        /**
         * How long a message stays ineligible for compaction.
         *
         * <p>This is a consumer's head start. A consumer reading the tail of a compacted topic
         * sees every message written within this window; past it, the cleaner may have already
         * replaced an update with a later one for the same key. Too short and a lagging consumer
         * silently skips intermediate states it was written to observe.
         *
         * @return the minimum compaction lag, in minutes
         */
        public int getMinCompactionLagMinutes() {
            return minCompactionLagMinutes;
        }

        public void setMinCompactionLagMinutes(int minCompactionLagMinutes) {
            this.minCompactionLagMinutes = minCompactionLagMinutes;
        }

        /**
         * Packages the consumer-side deserializer trusts to instantiate.
         *
         * <p>{@code JacksonJsonDeserializer} refuses to build a class outside this list, because a
         * deserializer with no such limit will construct whatever class name an attacker puts in the
         * message header - a deserialization gadget chain waiting to happen. Empty means "trust the
         * packages already scanned for {@code @EventContract} types", which is the narrowest default
         * that still works with no configuration: those are the only classes this service expects to
         * receive.
         *
         * @return the packages to trust, possibly empty
         */
        public List<String> getTrustedPackages() {
            return trustedPackages;
        }

        public void setTrustedPackages(List<String> trustedPackages) {
            this.trustedPackages = trustedPackages;
        }

        /**
         * Total delivery attempts - the first try plus retries - before a message is dead-lettered.
         *
         * <p>Bounded on purpose: a handler that retries forever stalls its partition, and every key
         * behind the poison message queues up behind it.
         *
         * @return the maximum number of attempts
         */
        public int getMaxDeliveryAttempts() {
            return maxDeliveryAttempts;
        }

        public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
            this.maxDeliveryAttempts = maxDeliveryAttempts;
        }

        /**
         * Fixed delay between redelivery attempts.
         *
         * @return the backoff, in milliseconds
         */
        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }
    }

    /**
     * Retry policy for every RabbitMQ task queue in this service.
     *
     * <p>Unlike a Kafka event's semantics, a classic queue's retry budget is not something a
     * {@code @TaskContract} declares - every consumer of a classic queue is already at-least-once
     * with no alternative, so there is nothing per-task to state beyond the queue's name. How hard
     * to retry before giving up is the one genuinely deployment-shaped knob left, the same as
     * {@link Kafka#getMaxDeliveryAttempts()}.
     */
    public static class Rabbit {

        private int maxDeliveryAttempts = 4;

        private long retryInitialIntervalMs = 1000;

        private double retryMultiplier = 2.0;

        private long retryMaxIntervalMs = 10_000;

        /**
         * Total delivery attempts - the first try plus retries - before a task is dead-lettered.
         *
         * @return the maximum number of attempts
         */
        public int getMaxDeliveryAttempts() {
            return maxDeliveryAttempts;
        }

        public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
            this.maxDeliveryAttempts = maxDeliveryAttempts;
        }

        /**
         * Delay before the first retry.
         *
         * @return the initial backoff, in milliseconds
         */
        public long getRetryInitialIntervalMs() {
            return retryInitialIntervalMs;
        }

        public void setRetryInitialIntervalMs(long retryInitialIntervalMs) {
            this.retryInitialIntervalMs = retryInitialIntervalMs;
        }

        /**
         * Growth factor applied to the backoff after each failed attempt.
         *
         * @return the backoff multiplier
         */
        public double getRetryMultiplier() {
            return retryMultiplier;
        }

        public void setRetryMultiplier(double retryMultiplier) {
            this.retryMultiplier = retryMultiplier;
        }

        /**
         * Ceiling the backoff never grows past, no matter how many attempts remain.
         *
         * @return the maximum backoff, in milliseconds
         */
        public long getRetryMaxIntervalMs() {
            return retryMaxIntervalMs;
        }

        public void setRetryMaxIntervalMs(long retryMaxIntervalMs) {
            this.retryMaxIntervalMs = retryMaxIntervalMs;
        }
    }
}
