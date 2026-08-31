package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wiring: the only place allowed to see every layer at once.
 *
 * <p>Configuration classes exist so that no other class has to know how the pieces are
 * assembled. Keeping them free of logic is what makes that trade worthwhile - a
 * decision taken inside a configuration class is a decision no test will ever reach.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.CONFIGURATION, principle = "P-011")
public @interface ArchConfig {}
