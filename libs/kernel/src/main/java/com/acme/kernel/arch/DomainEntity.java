package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A domain object with an identity that outlives its attribute values.
 *
 * <p>Entities live inside an aggregate and are reached through its root. An entity that
 * needs to be loaded on its own is an aggregate root that has not been recognised yet.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-020")
public @interface DomainEntity {}
