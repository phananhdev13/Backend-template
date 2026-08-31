package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An immutable value defined entirely by its attributes.
 *
 * <p>Value objects are where domain validation belongs. A type that cannot be
 * constructed in an invalid state removes a whole class of checks from every use case
 * that handles it, so prefer {@code Money}, {@code EmailAddress} and {@code Quantity}
 * over {@code BigDecimal}, {@code String} and {@code int}.
 *
 * <p>Enforced to be a record or a final class with no mutable state.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-021")
public @interface ValueObject {}
