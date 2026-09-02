package com.acme.security.opa;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The OPA sidecar's decision endpoint for exactly one policy path, configured once per
 * deployment via {@code spring.http.serviceclient.opa.base-url} - not a generic
 * {@code /v1/data/{path}} client, because a path variable containing {@code /} gets
 * percent-encoded by the underlying {@code RestClient} and never reaches OPA as a path
 * segment at all. A service that needs more than one decision endpoint configures more than
 * one {@code base-url}-scoped group, the same way it would for any other HTTP dependency.
 *
 * <p>Confirmed empirically against a real {@code openpolicyagent/opa:1.20.1} container: a
 * loaded policy answers {@code {"result": true|false}}; a path with no policy loaded under it
 * answers {@code {}} - no {@code result} key at all, which {@link OpaDecisionResponse} must
 * read as {@code null}, not as a parse failure.
 */
@HttpExchange
public interface OpaClient {

    @PostExchange
    OpaDecisionResponse decide(@RequestBody OpaDecisionRequest request);
}
