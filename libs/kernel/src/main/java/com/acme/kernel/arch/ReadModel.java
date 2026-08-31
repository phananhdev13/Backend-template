package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A query answered directly from storage, shaped for the caller rather than for the domain.
 *
 * <p>Reads and writes have different needs, and forcing a list screen to hydrate
 * aggregates is how a service gets slow. A read model is allowed to bypass the domain
 * and project straight from the database, precisely because it changes nothing.
 *
 * <p>The rule that buys that freedom: a read model must be side-effect free. Structural
 * tests hold it to that.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-032")
public @interface ReadModel {

    /** Stable identifier, {@code QRY-<CONTEXT>-<NNN>}. */
    String id();
}
