package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Points at the architecture decision record that explains a choice a reader would
 * otherwise want to undo.
 *
 * <p>Reach for this on the code that looks wrong until you know the context: the manual
 * mapper where a library would do, the second cache, the deliberately duplicated model.
 * Without the pointer, the next person deletes it and rediscovers the reason in
 * production.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Adr {

    /** Identifiers such as {@code "ADR-0007"}, resolving to {@code docs/adr/0007-*.md}. */
    String[] value();
}
