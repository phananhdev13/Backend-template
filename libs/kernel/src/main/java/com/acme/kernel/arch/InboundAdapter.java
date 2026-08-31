package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Translates an incoming request in some protocol into a call on an {@link InputPort}.
 *
 * <p>An inbound adapter's whole job is translation: parse, validate the shape, map to a
 * command, call the port, map the answer back. Business decisions taken here are
 * invisible to every other entry point, which is how the same rule ends up implemented
 * twice and differently once a message consumer is added beside the controller.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.ADAPTER, principle = "P-040")
public @interface InboundAdapter {

    /** Protocol this adapter accepts. */
    AdapterKind value();
}
