package com.acme.order;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith's own view of this service's module boundaries.
 *
 * <p>P-100 has described this test since it was written; it did not exist, so the narrow
 * {@code allowedDependencies} the principle tells you to declare were decorative and the cycle
 * check it promises never ran. {@code BoundaryRules} in {@code libs/arch-test} deliberately
 * overlaps: Modulith checks declared module dependencies and cycles, the ArchUnit rules add the
 * {@code @PublicApi}/{@code @Internal} semantics Modulith knows nothing about.
 *
 * <p>This is a plain unit test - {@code ApplicationModules.of} reads bytecode, it does not start a
 * context - so it costs a fraction of a second and needs no container.
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(OrderServiceApplication.class);

    @Test
    void modulesAreAcyclicAndRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }
}
