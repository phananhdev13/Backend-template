package com.acme.agentfactory.registry.domain;

import com.acme.kernel.arch.ValueObject;

/**
 * Which model a version runs on.
 *
 * <p>Provider and model id travel together because a model id alone is ambiguous across
 * providers, and the registry never validates either against a live catalogue - this is metadata,
 * not a deployment target.
 */
@ValueObject
public record ModelRef(String provider, String modelId) {

    public ModelRef {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("A model reference needs a provider");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("A model reference needs a model id");
        }
    }
}
