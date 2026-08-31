package com.acme.messaging;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Duration;

/**
 * An {@link EventContract} read off an event type and turned into data.
 *
 * <p>The annotation is the declaration; this is the parsed form the binders work from. Keeping the
 * two apart means the reflective read happens once per event type rather than once per message,
 * and - more usefully - that a provisioner can be handed a descriptor in a unit test without an
 * annotated class to hang it on.
 *
 * <p>Every component is a promise some consumer depends on. None of them has a sensible runtime
 * default, which is why they are all required here even though the annotation defaults several of
 * them: a descriptor with a missing field would be a contract nobody declared.
 *
 * @param stream logical name in {@code <context>.<event>} form
 * @param version contract version; incompatible changes get a new one and a new stream
 * @param partitionKey name of the event component whose value orders and identifies a message
 * @param payload whether the message states a change or a current value
 * @param retention how the broker ages messages out
 * @param retentionDays window for the two retention modes that have one
 * @param delivery how many times a consumer may observe a message
 * @param ordering the order a consumer may rely on
 * @param schema repository-relative path to the published schema
 * @param containsPersonalData whether the payload carries personal data
 * @param eventType the annotated type, kept so failures can name the class a human has to edit
 */
public record StreamDescriptor(
        String stream,
        int version,
        String partitionKey,
        PayloadKind payload,
        StreamRetention retention,
        int retentionDays,
        DeliveryGuarantee delivery,
        OrderingGuarantee ordering,
        String schema,
        boolean containsPersonalData,
        Class<? extends DomainEvent> eventType) {

    /**
     * The name the broker sees, in {@code <prefix>.<stream>.v<version>} form.
     *
     * <p>The version is part of the physical name because an incompatible contract change has to
     * become a second stream that runs beside the first: consumers migrate on their own schedule,
     * and the old stream is deleted when it has no readers, not when the new one works.
     *
     * <p>The prefix is how one broker hosts several environments or tenants without their streams
     * colliding. A blank prefix yields the bare name, so local development needs no configuration.
     *
     * @param prefix environment or tenant prefix; blank for none
     * @return the physical stream name
     */
    public String physicalName(String prefix) {
        String logical = stream + ".v" + version;
        if (prefix == null || prefix.isBlank()) {
            return logical;
        }
        // Tolerate a trailing separator in configuration. "acme." and "acme" are the same
        // intent, and an empty name segment is a topic that is merely annoying to debug.
        String trimmed = prefix.strip();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? logical : trimmed + "." + logical;
    }

    /**
     * Whether the broker is being asked to keep only the latest message per key.
     *
     * <p>Both compacting modes behave the same way for every decision that matters outside the
     * retention window itself: tombstones become meaningful, the payload must be a whole state
     * snapshot, and brokers without compaction cannot host the stream at all.
     *
     * @return true for {@link StreamRetention#COMPACTED} and
     *     {@link StreamRetention#COMPACTED_AND_WINDOWED}
     */
    public boolean isCompacted() {
        return retention == StreamRetention.COMPACTED || retention == StreamRetention.COMPACTED_AND_WINDOWED;
    }

    /**
     * The retention window as a duration.
     *
     * <p>Meaningful only for the two modes that bound history by time; the value is still returned
     * for the others so callers do not have to branch before they know whether they need it.
     *
     * @return the declared window
     */
    public Duration retentionWindow() {
        return Duration.ofDays(retentionDays);
    }
}
