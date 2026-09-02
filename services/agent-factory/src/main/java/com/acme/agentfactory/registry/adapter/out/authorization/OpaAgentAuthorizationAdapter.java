package com.acme.agentfactory.registry.adapter.out.authorization;

import com.acme.agentfactory.registry.application.port.out.AgentAuthorizationPort;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.security.Actor;
import com.acme.security.opa.OpaAuthorization;
import java.util.Map;

/**
 * Asks the OPA sidecar whether an actor may activate a specific agent's version.
 *
 * <p>{@link OpaAuthorization} already owns the fail-closed default and the input shape; this
 * adapter's only job is naming the action and the resource this port's one question needs -
 * exactly what P-120 asks of a remote-policy-engine adapter: a timeout ({@code
 * spring.http.serviceclient.opa.*} in application.yml) and a documented failure choice.
 */
@OutboundAdapter(port = AgentAuthorizationPort.class, kind = AdapterKind.HTTP_CLIENT)
@ImplementsPrinciple(
        value = {"P-051", "P-120"},
        note = "500ms connect / 1s read timeout in application.yml; OpaAuthorization fails closed on "
                + "any OPA failure, so an unreachable sidecar denies activation rather than allowing it.")
public class OpaAgentAuthorizationAdapter implements AgentAuthorizationPort {

    private final OpaAuthorization opaAuthorization;

    public OpaAgentAuthorizationAdapter(OpaAuthorization opaAuthorization) {
        this.opaAuthorization = opaAuthorization;
    }

    @Override
    public boolean canActivateVersion(Actor actor, AgentId agentId) {
        return opaAuthorization.check(actor, "activate-agent-version", Map.of("agentId", agentId.value()));
    }
}
