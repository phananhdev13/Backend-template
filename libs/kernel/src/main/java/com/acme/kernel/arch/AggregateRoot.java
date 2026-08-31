package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The entry point to a consistency boundary.
 *
 * <p>An aggregate root owns the invariants of everything inside it and is the only
 * member of that cluster the rest of the system may hold a reference to. One
 * transaction changes one aggregate; anything wider is a saga, not a transaction.
 *
 * <p>Repositories exist per aggregate root and nowhere else, which is why
 * {@code OutputPort} repository interfaces are checked against this annotation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-020")
public @interface AggregateRoot {

    /** Name of the consistency boundary, when it differs from the class name. */
    String value() default "";
}
