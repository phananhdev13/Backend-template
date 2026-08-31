package com.acme.kernel.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A business rule that varies independently of the aggregate it applies to.
 *
 * <p>Pricing, eligibility and discount rules change on a different clock from the
 * objects they judge. Naming them makes the rule findable and swappable instead of
 * buried in an {@code if} inside a use case.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.DOMAIN, principle = "P-022")
public @interface DomainPolicy {

    /** The decision this policy makes, in the language of the business. */
    String decides();
}
