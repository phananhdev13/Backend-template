package com.acme.messaging;

import com.acme.kernel.event.DomainEvent;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes to Kafka under the semantics the event's contract declares.
 *
 * <p>The caller passes an event and nothing else. Topic, key and headers are all derived from the
 * contract, which is what stops two call sites publishing the same event under different keys and
 * quietly destroying per-key ordering for half the stream.
 */
public final class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Header names are part of the wire contract; consumers in other repositories read them. */
    static final String HEADER_EVENT_TYPE = "event-type";

    static final String HEADER_CONTRACT_VERSION = "contract-version";
    static final String HEADER_CORRELATION_ID = "correlation-id";

    private final KafkaTemplate<String, Object> template;
    private final String streamPrefix;

    public KafkaEventPublisher(KafkaTemplate<String, Object> template, MessagingProperties properties) {
        this.template = template;
        this.streamPrefix = properties.getStreamPrefix();
    }

    @Override
    public void publish(DomainEvent event) {
        StreamDescriptor descriptor = EventContracts.describe(event.getClass());
        String key = EventContracts.partitionKeyValueOf(event);
        send(descriptor, key, event);
        log.debug("Published {} to {} key={}", event.getClass().getSimpleName(), descriptor.stream(), key);
    }

    @Override
    public void publishTombstone(Class<? extends DomainEvent> eventType, String partitionKey) {
        StreamDescriptor descriptor = EventContracts.describe(eventType);
        if (!descriptor.isCompacted()) {
            throw new UnsupportedContractException(("%s is published to '%s', which has retention=%s. A tombstone on a "
                            + "stream that is not compacted reaches consumers as a null-payload message they have no "
                            + "rule for. Either declare retention=COMPACTED with payload=STATE_SNAPSHOT, or publish an "
                            + "explicit deletion event instead.")
                    .formatted(eventType.getName(), descriptor.stream(), descriptor.retention()));
        }
        send(descriptor, partitionKey, null);
        log.info("Published tombstone to {} key={}", descriptor.stream(), partitionKey);
    }

    private void send(StreamDescriptor descriptor, String key, Object payload) {
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(descriptor.physicalName(streamPrefix), null, null, key, payload);
        header(record, HEADER_EVENT_TYPE, descriptor.eventType().getName());
        header(record, HEADER_CONTRACT_VERSION, String.valueOf(descriptor.version()));
        // Carrying correlation across the broker is what keeps one request one identifier; a
        // chain that loses it here breaks exactly where the hard bugs are.
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            header(record, HEADER_CORRELATION_ID, correlationId);
        }
        template.send(record);
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
