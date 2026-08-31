package com.acme.kernel.event;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An inbound adapter that consumes a stream and drives a use case.
 *
 * <p>Declaring what it consumes lets the build check the pair rather than each side
 * alone: a handler of an {@link DeliveryGuarantee#AT_LEAST_ONCE} stream must be
 * {@link Idempotent}, and a handler that relies on ordering must consume a stream that
 * promises it. Both are checked by {@code EventContractRules}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.ADAPTER, principle = "P-042")
public @interface EventHandler {

    /** The event type this handler consumes. Its {@link EventContract} supplies the semantics. */
    Class<? extends DomainEvent> consumes();

    /**
     * Consumer group identifier.
     *
     * <p>Two deployments sharing a group share the work; two using different groups each
     * get every message. Getting this wrong is how an email goes out once per instance.
     */
    String group();
}
