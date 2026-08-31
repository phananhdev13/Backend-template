package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One thing a user or another system can ask this service to do.
 *
 * <p>The use case is the unit this codebase is organised around. It is the transaction
 * boundary, the authorisation boundary, and the unit a test exercises. It orchestrates
 * domain objects through ports and contains no business rules of its own: a use case
 * that computes rather than coordinates has absorbed logic that belongs in the domain.
 *
 * <p>A use case implements exactly one {@link InputPort}. That is what keeps the class
 * from growing into a service object with fourteen unrelated methods, and it is checked
 * by {@code UseCaseRules.useCasesImplementExactlyOneInputPort()}.
 *
 * <p>The {@link #id()} is the thread that ties a request in production back to the
 * specification that asked for it: it appears in {@code docs/use-cases}, in the
 * structured log line the use case emits, and in the trace span name.
 *
 * <p>This annotation carries no Spring meaning on purpose - {@code kernel} does not
 * depend on a framework. Services turn these classes into beans with a component-scan
 * include filter; see {@code OrderServiceApplication}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-030")
public @interface UseCase {

    /**
     * Stable identifier, {@code UC-<CONTEXT>-<NNN>}, resolving to a file under
     * {@code docs/use-cases}. Checked for existence by
     * {@code TraceabilityRules.everyUseCaseIsDocumented()}.
     */
    String id();

    /** What the caller gets out of it, phrased as the business would phrase it. */
    String value() default "";
}
