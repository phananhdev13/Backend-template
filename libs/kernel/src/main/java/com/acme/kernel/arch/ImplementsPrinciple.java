package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records that this class exists in the shape it does because of a named principle.
 *
 * <p>Roles already imply their governing principle. This annotation is for the extra
 * claim: an outbox writer citing the delivery principle, a rate limiter citing the
 * resilience principle. It makes "why is this here?" answerable from the class itself,
 * and it makes the reverse question - "what actually implements P-051?" - answerable by
 * a test rather than by a search.
 *
 * <p>{@code docs/reference/principle-map.md} is generated from these; a principle with
 * no implementation and no explicit waiver fails the build.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ImplementsPrinciple {

    /** Principle identifiers, for example {@code {"P-051", "P-052"}}. */
    String[] value();

    /** How this class implements them, where the connection is not obvious. */
    String note() default "";
}
