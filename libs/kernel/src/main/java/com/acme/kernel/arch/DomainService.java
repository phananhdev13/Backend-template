package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Domain logic that belongs to no single aggregate.
 *
 * <p>Use sparingly. A domain service that grows methods faster than the aggregates
 * around it is usually a sign that an aggregate is missing, and that the logic has
 * drifted out of the objects it describes into a procedural shell.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-022")
public @interface DomainService {}
