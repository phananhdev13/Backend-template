package com.acme.security.opa;

import com.acme.kernel.security.Actor;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

/**
 * Shapes an OPA decision input from an {@link Actor} and calls the sidecar - and, per
 * P-120's "choose on purpose" for a remote policy engine, fails closed on any error: a
 * timeout, a connection refusal, or a non-2xx from OPA itself all deny rather than allow.
 *
 * <p>This is deliberately <b>not</b> wired into {@code @PreAuthorize} SpEL. P-120 already
 * rejects that shape for ownership checks - "leads to {@code @PreAuthorize} expressions
 * containing bean calls that hit the database, which is logic in a string, untested and
 * invisible to the debugger" - and a network call to a policy sidecar is the same problem,
 * worse: it also has no {@code @OutputPort} to declare a timeout against
 * ({@code ResilienceRules.remoteCallsDeclareTimeouts}) and no seam a test can substitute. Call
 * {@link #check} from inside the {@code @UseCase}, behind the service's own {@code @OutputPort}
 * and {@code @OutboundAdapter}, exactly where P-120 says a remote policy engine belongs.
 */
public final class OpaAuthorization {

    private static final Logger log = LoggerFactory.getLogger(OpaAuthorization.class);

    private final OpaClient opaClient;

    public OpaAuthorization(OpaClient opaClient) {
        this.opaClient = opaClient;
    }

    /**
     * @param actor who is asking
     * @param action the name of the operation being attempted, e.g. {@code "activate-agent-version"}
     * @param resource whatever state the policy needs to decide - already loaded by the caller,
     *     the same "fine-grained checks happen after the load" shape P-120 already requires
     * @return {@code true} only if OPA was reachable and explicitly answered {@code true}
     */
    public boolean check(Actor actor, String action, Map<String, Object> resource) {
        Map<String, Object> input = Map.of(
                "actor",
                Map.of("subject", actor.subject(), "roles", actor.roles()),
                "action",
                action,
                "resource",
                resource);
        try {
            OpaDecisionResponse response = opaClient.decide(new OpaDecisionRequest(input));
            return response != null && Boolean.TRUE.equals(response.result());
        } catch (RestClientException e) {
            log.warn("OPA decision for action '{}' failed closed: {}", action, e.toString());
            return false;
        }
    }
}
