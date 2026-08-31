package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as belonging to one feature module and unsupported anywhere else.
 *
 * <p>Public for wiring reasons, off-limits by contract. Structural tests fail any
 * reference from another feature module, which is what allows the owning module to
 * change it without a survey of the codebase.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Internal {}
