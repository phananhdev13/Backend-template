package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Implements an {@link OutputPort} against a real technology.
 *
 * <p>Naming the port it implements is what makes the hexagon navigable in both
 * directions: from a port you can find every implementation, and a structural test can
 * assert that every port has one and that no adapter implements a port nobody declared.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.ADAPTER, principle = "P-041")
public @interface OutboundAdapter {

    /** The port this class satisfies. */
    Class<?> port();

    /** The technology it uses to satisfy it. */
    AdapterKind kind();
}
