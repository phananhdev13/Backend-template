package com.acme.kernel.arch;

/**
 * The four layers of a service, ordered from the centre outwards.
 *
 * <p>This enum is the single source of truth for the dependency rule. The ArchUnit
 * rule {@code LayeringRules.dependenciesPointInwards()} asks {@link #mayDependOn}
 * rather than restating the matrix, so changing the rule here changes the build.
 */
public enum Layer {

    /** Business meaning. Knows no framework, no database, no transport. */
    DOMAIN,

    /** Orchestration of a single use case, expressed against ports. */
    APPLICATION,

    /** Translation between the outside world and a port. Knows a technology. */
    ADAPTER,

    /** Wiring. Knows every layer, and is known by none. */
    CONFIGURATION;

    /**
     * Whether a class in this layer is allowed to reference a class in {@code other}.
     *
     * <p>Dependencies point inwards. The domain is the fixed point: it may only depend
     * on itself, which is what makes it testable without infrastructure and portable
     * across the transports and brokers layered around it.
     */
    public boolean mayDependOn(Layer other) {
        return switch (this) {
            case DOMAIN -> other == DOMAIN;
            case APPLICATION -> other == DOMAIN || other == APPLICATION;
            case ADAPTER -> other != CONFIGURATION;
            case CONFIGURATION -> true;
        };
    }
}
