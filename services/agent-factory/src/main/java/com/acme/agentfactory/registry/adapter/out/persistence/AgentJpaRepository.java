package com.acme.agentfactory.registry.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data's view of the agents table.
 *
 * <p>An implementation detail of this package, exactly as {@code OrderJpaRepository} is for
 * orders: the application depends on {@code AgentRepository}, the port, never on this interface.
 */
interface AgentJpaRepository extends JpaRepository<AgentEntity, String> {

    boolean existsByName(String name);
}
