package com.acme.agentfactory.registry.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** The wire format for registering an agent. Never the domain model; see {@code NamingRules}. */
public record RegisterAgentRequest(
        @NotBlank String name,
        @NotBlank String provider,
        @NotBlank String modelId,
        String systemPrompt,
        List<String> tools) {}
