package com.acme.agentfactory.registry.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** The wire format for proposing a new version of an existing agent. */
public record AddAgentVersionRequest(
        @NotBlank String provider, @NotBlank String modelId, String systemPrompt, List<String> tools) {}
