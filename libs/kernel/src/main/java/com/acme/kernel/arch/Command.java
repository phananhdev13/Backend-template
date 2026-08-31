package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The request a caller hands to a use case.
 *
 * <p>A command belongs to the application layer, not the domain: it is the shape of an intent, and
 * it changes when the API changes rather than when the business rules do. Keeping it distinct from
 * a {@link ValueObject} is what stops an HTTP payload's shape leaking into the model.
 *
 * <p>Commands carry domain types - {@code Money}, {@code CustomerId} - not the strings and decimals
 * they arrived as. Parsing happens in the inbound adapter, so that by the time a use case is
 * called, whole categories of invalid input no longer exist.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-030")
public @interface Command {}
