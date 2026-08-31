package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Something the application needs from the outside world, expressed as the application
 * would ask for it.
 *
 * <p>Output ports are owned by the layer that <em>uses</em> them, not the one that
 * implements them. That inversion is what keeps a database or broker choice from
 * reaching into the use case, and it is why the signature must speak in domain types:
 * an output port that mentions a {@code ResultSet}, a {@code ConsumerRecord} or an
 * HTTP status has leaked its implementation and defeats the purpose.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-031")
public @interface OutputPort {}
