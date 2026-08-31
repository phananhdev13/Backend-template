package com.acme.kernel.event;

import com.acme.kernel.arch.ImplementsPrinciple;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * States that handling this message twice has the same effect as handling it once, and
 * names the key that makes it so.
 *
 * <p>At-least-once delivery is not a broker deficiency to be worked around; it is the
 * only guarantee a distributed system can keep cheaply. The cost is paid here, once per
 * handler, by recording what has already been processed.
 *
 * <p>Idempotency is not the same as de-duplication over a time window. A retry can
 * arrive after a broker outage, a consumer rebalance, or a replay months later, so the
 * {@link #retentionHours()} below is a storage bound, not a correctness argument. Where
 * correctness must hold indefinitely, make the write itself idempotent - an upsert keyed
 * by the business identifier, or a state transition that is a no-op from its own target
 * state - and say so in {@link #note()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ImplementsPrinciple("P-071")
public @interface Idempotent {

    /**
     * Name of the message field whose value identifies a logical delivery, for example
     * {@code "orderId"} or {@code "eventId"}.
     *
     * <p>Must be stable across retries of the same logical event. A freshly generated
     * identifier per publish attempt de-duplicates nothing.
     */
    String key();

    /** How long the de-duplication record is kept. */
    int retentionHours() default 168;

    /** Why this handler is safe to repeat, when the key alone does not explain it. */
    String note() default "";
}
