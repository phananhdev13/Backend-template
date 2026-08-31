package com.acme.kernel.event;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The published contract of an event stream, declared on the event that flows through it.
 *
 * <p>Everything here is a decision a consumer depends on and cannot discover: the key
 * that defines ordering, whether history survives, how many times a message may arrive.
 * Declaring it in code rather than in broker configuration means the compiler sees it,
 * review sees it, and the tests in {@code libs/arch-test} can reject combinations that
 * cannot hold - a compacted stream of facts, a per-key ordering promise with no key, an
 * at-least-once stream whose handler is not idempotent.
 *
 * <p>{@code libs/messaging-support} is the only code that reads this at runtime. It
 * provisions a Kafka topic or a RabbitMQ stream from it, so the broker configuration is
 * derived from the declaration instead of drifting away from it.
 *
 * <p><b>Changing a contract.</b> Adding an optional field is compatible. Anything else -
 * removing a field, changing a type, changing the key or the retention - is a new
 * {@link #version()} on a new stream, run in parallel until consumers migrate. The old
 * stream is deleted when it has no consumers, not when the new one works.
 *
 * <p>See {@code docs/principles/P-070-event-semantics.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-070")
public @interface EventContract {

    /**
     * Logical stream name in {@code <context>.<event>} form, for example
     * {@code "orders.order-placed"}.
     *
     * <p>Physical names are derived per broker and per environment; nothing outside
     * {@code messaging-support} constructs one.
     */
    String stream();

    /** Contract version. Incompatible changes increment it and get their own stream. */
    int version() default 1;

    /**
     * Name of the record component that keys the stream, for example {@code "orderId"}.
     *
     * <p>Determines partitioning, ordering and - for compacted streams - identity.
     * Verified against the record's actual components, because a key naming a field that
     * no longer exists degrades to random partitioning and takes ordering with it.
     */
    String partitionKey();

    /** Whether the message states a change or a current value. */
    PayloadKind payload() default PayloadKind.FACT;

    /** How the broker ages messages out. */
    StreamRetention retention() default StreamRetention.TIME_WINDOW;

    /** Window for {@link StreamRetention#TIME_WINDOW} and {@link StreamRetention#COMPACTED_AND_WINDOWED}. */
    int retentionDays() default 7;

    /** How many times a consumer may observe a message. */
    DeliveryGuarantee delivery() default DeliveryGuarantee.AT_LEAST_ONCE;

    /** The order a consumer may rely on. */
    OrderingGuarantee ordering() default OrderingGuarantee.PER_KEY;

    /**
     * Path to the schema, relative to the repository root, for example
     * {@code "contracts/events/orders.order-placed.v1.json"}.
     *
     * <p>A published event is an API. Its schema is checked in beside the code and its
     * existence is asserted by {@code EventContractRules}, so a consumer in another
     * repository has something to generate from.
     */
    String schema();

    /**
     * Whether the payload carries personal data.
     *
     * <p>Drives log redaction and, on a compacted stream, forces the erasure question to
     * be answered: a tombstone removes the current value, but earlier copies survive in
     * uncompacted segments for as long as the broker chooses.
     */
    boolean containsPersonalData() default false;
}
