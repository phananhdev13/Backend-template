package com.acme.agentfactory;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith's own view of this service's module boundaries. See {@code order-service}'s
 * {@code ModularityTest} for the reasoning; this is the same test, once per service because the
 * module graph is per service.
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(AgentFactoryApplication.class);

    @Test
    void modulesAreAcyclicAndRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }
}
