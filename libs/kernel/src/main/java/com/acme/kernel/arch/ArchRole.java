package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that turns a role annotation into something the build can reason about.
 *
 * <p>Applied to annotations, never to classes. A role annotation declares which
 * {@link Layer} its classes live in and which principle document justifies the
 * constraints on them. Structural tests discover roles through this meta-annotation, so
 * a new role added here is enforced without editing any rule.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ArchRole {

    /** The layer that classes carrying this role belong to. */
    Layer layer();

    /**
     * Identifier of the governing principle, for example {@code "P-020"}, resolving to
     * {@code docs/principles/P-020-*.md}. Rule failures quote it so a red build points
     * at its own explanation.
     */
    String principle();
}
