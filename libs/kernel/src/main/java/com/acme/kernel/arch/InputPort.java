package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The interface through which the outside world drives a use case.
 *
 * <p>Declared by the application layer and called by adapters, so that a controller, a
 * message consumer and a scheduled job all enter the same code by the same door. An
 * input port has a single method: two methods mean two use cases.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-030")
public @interface InputPort {}
