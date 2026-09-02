package com.acme.security.opa;

import java.util.Map;

/** OPA's own REST Data API request shape: {@code {"input": {...}}}. */
public record OpaDecisionRequest(Map<String, Object> input) {}
