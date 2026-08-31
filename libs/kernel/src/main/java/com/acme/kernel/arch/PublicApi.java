package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as part of a feature module's supported surface.
 *
 * <p>Java's {@code public} says "reachable", not "supported". This annotation says the
 * second thing. Anything a sibling feature module depends on must carry it, so that the
 * cost of a breaking change is visible at the point of change rather than at the point
 * of breakage.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PublicApi {

    /** Contract version, bumped when the shape changes incompatibly. */
    int since() default 1;
}
