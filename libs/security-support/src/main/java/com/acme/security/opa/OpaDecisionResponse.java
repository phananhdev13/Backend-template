package com.acme.security.opa;

/**
 * OPA's own REST Data API response shape: {@code {"result": true|false}} when a rule is
 * loaded at the queried path, {@code {}} - {@link #result()} deserialises as {@code null},
 * never a boolean - when nothing is loaded there. Callers must treat {@code null} the same as
 * {@code false}: an unconfigured or unreachable policy path fails closed, never open.
 */
public record OpaDecisionResponse(Boolean result) {}
