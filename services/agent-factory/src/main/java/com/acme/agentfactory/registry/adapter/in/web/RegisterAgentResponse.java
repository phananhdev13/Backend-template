package com.acme.agentfactory.registry.adapter.in.web;

/**
 * What the caller gets back: the identifier they will use for everything else.
 *
 * <p>No version field - registration always creates version 1, a documented invariant of
 * {@code UC-AGT-001} rather than something the response needs to state.
 */
public record RegisterAgentResponse(String agentId) {}
