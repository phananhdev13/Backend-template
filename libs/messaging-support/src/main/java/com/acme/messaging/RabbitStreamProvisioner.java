package com.acme.messaging;

import com.acme.kernel.event.OrderingGuarantee;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Translates a {@link StreamDescriptor} into a RabbitMQ stream queue, or refuses to.
 *
 * <p>RabbitMQ Streams are an append-only log with age- and size-based truncation, which covers
 * {@code TIME_WINDOW} and {@code INFINITE} exactly. They have no compaction: there is no argument,
 * no policy and no plugin that keeps the latest message per key and discards the rest.
 *
 * <p>So the interesting behaviour of this class is the refusal. A binder that "supported"
 * compaction on RabbitMQ by falling back to {@code x-max-age} would boot cleanly, pass every test
 * that publishes and consumes a message, and convert a promise of "the current value of every key,
 * kept forever" into "whatever happened to arrive recently". The keys that go missing are exactly
 * the quiet ones - the entities nobody has updated - and their absence shows up when a consumer
 * rebuilds from the stream and produces a view that is incomplete without being wrong-looking.
 * There is no alert for that. There is a boot failure for it, here.
 */
public final class RabbitStreamProvisioner {

    private static final Logger log = LoggerFactory.getLogger(RabbitStreamProvisioner.class);

    /** RabbitMQ's stream age argument. Values are {@code <number><unit>}, units Y M D h m s. */
    private static final String MAX_AGE_ARGUMENT = "x-max-age";

    private final MessagingProperties properties;

    /**
     * @param properties supplies the stream name prefix; retention comes from the contract
     */
    public RabbitStreamProvisioner(MessagingProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the stream queues for a set of contracts.
     *
     * @param descriptors the contracts to provision
     * @return one durable stream queue per contract, in the order given
     * @throws UnsupportedContractException on the first contract RabbitMQ cannot honour
     */
    public List<Queue> toStreamQueues(Collection<StreamDescriptor> descriptors) {
        List<Queue> queues = new ArrayList<>(descriptors.size());
        for (StreamDescriptor descriptor : descriptors) {
            queues.add(toStreamQueue(descriptor));
        }
        return queues;
    }

    /**
     * Builds the stream queue for one contract.
     *
     * @param descriptor the contract to translate
     * @return a durable stream queue configured to match the declared retention
     * @throws UnsupportedContractException if the contract asks for compaction, which RabbitMQ
     *     Streams cannot provide at any setting
     */
    public Queue toStreamQueue(StreamDescriptor descriptor) {
        rejectCompaction(descriptor);
        warnAboutGlobalOrdering(descriptor);
        String name = descriptor.physicalName(properties.getStreamPrefix());
        QueueBuilder builder = QueueBuilder.durable(name).stream();
        switch (descriptor.retention()) {
            case TIME_WINDOW -> {
                // Truncation is by whole segment, so the stream holds at least this age and
                // usually a little more. Treat the window as a floor, never as an erasure deadline.
                builder = builder.withArgument(MAX_AGE_ARGUMENT, descriptor.retentionDays() + "D");
            }
            case INFINITE -> {
                // No x-max-age at all: any value here would be a smaller promise than the contract
                // makes, and an absent argument is the only way to say "keep everything".
                log.info("Stream {} is declared INFINITE; no x-max-age will be set", name);
            }
            case COMPACTED, COMPACTED_AND_WINDOWED ->
                throw new IllegalStateException("unreachable: compaction is rejected above");
        }
        Queue queue = builder.build();
        log.info("Provisioning RabbitMQ stream {} with arguments {}", name, queue.getArguments());
        return queue;
    }

    /**
     * The refusal this class exists for.
     *
     * <p>Written to be read by whoever hits it at three in the morning, with no knowledge of this
     * module: what is impossible, why it is impossible rather than merely unimplemented, what
     * would have gone wrong if it had been allowed, and the two changes that fix it.
     */
    private static void rejectCompaction(StreamDescriptor descriptor) {
        if (!descriptor.isCompacted()) {
            return;
        }
        throw new UnsupportedContractException(("%s declares stream \"%s\" v%d with retention=%s, and this "
                        + "application is binding it to RabbitMQ.%n%n"
                        + "RabbitMQ Streams cannot compact. A stream is an append-only log that truncates "
                        + "from the oldest end by age or by size; there is no argument, policy or plugin "
                        + "that keeps the latest message per key and drops the superseded ones. This is a "
                        + "property of the broker, not a gap in this module.%n%n"
                        + "Provisioning it anyway - by falling back to x-max-age - would turn \"the current "
                        + "value of every %s, kept indefinitely\" into \"whatever was published recently\". "
                        + "The keys that vanish first are the ones nobody has updated, so nothing errors "
                        + "and no consumer notices until one rebuilds its view from the stream and gets an "
                        + "answer that is quietly incomplete. That is why this is a startup failure.%n%n"
                        + "Resolve it one of two ways:%n"
                        + "  1. Route this contract to Kafka, whose cleanup.policy=compact is what the "
                        + "declaration is asking for. Compacted state streams are a Kafka workload; leave "
                        + "the fact streams on RabbitMQ if that is where they belong.%n"
                        + "  2. Change the contract to retention=TIME_WINDOW (or INFINITE) and give "
                        + "consumers a separate way to load initial state - a query API or a periodic full "
                        + "snapshot - because a windowed stream can no longer serve as one.")
                .formatted(
                        descriptor.eventType().getName(),
                        descriptor.stream(),
                        descriptor.version(),
                        descriptor.retention(),
                        descriptor.partitionKey()));
    }

    private static void warnAboutGlobalOrdering(StreamDescriptor descriptor) {
        if (descriptor.ordering() != OrderingGuarantee.GLOBAL) {
            return;
        }
        // A plain stream is a single log, so global order holds for free here - unlike Kafka,
        // where it costs the partition count. Worth saying out loud, because it stops holding the
        // moment someone converts this into a super-stream to get throughput back.
        log.info(
                "Stream {} declares GLOBAL ordering, which a single RabbitMQ stream satisfies inherently. "
                        + "Partitioning it into a super-stream would break that promise.",
                descriptor.stream());
    }
}
