package com.acme.agentfactory.registry.application.port.out;

import com.acme.agentfactory.registry.domain.AgentDefinition;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.kernel.arch.OutputPort;
import java.util.Optional;

/** What the application needs from storage, in the application's own words. */
@OutputPort
public interface AgentRepository {

    void save(AgentDefinition agent);

    Optional<AgentDefinition> findById(AgentId id);

    boolean existsByName(String name);
}
