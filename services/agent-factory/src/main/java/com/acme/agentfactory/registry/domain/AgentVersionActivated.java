package com.acme.agentfactory.registry.domain;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Instant;

/**
 * A specific version of an agent became the active one.
 *
 * <p>A fact - it states a transition, not the agent's current configuration, so anything wanting
 * the full picture (model, prompt, tools) calls back into this service rather than expecting the
 * event to carry it. That keeps the event small and stable while a version's content can be as
 * large as a system prompt gets.
 *
 * <p>{@code payload = FACT} rules out compaction: a stream of activations is a history, and
 * compacting it would let a consumer that replays from zero see only the latest activation per
 * agent, silently losing every earlier one it may have needed to react to.
 */
@EventContract(
        stream = "agents.agent-version-activated",
        version = 1,
        partitionKey = "agentId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        retentionDays = 90,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/agents.agent-version-activated.v1.json")
public record AgentVersionActivated(String agentId, int version, Instant occurredAt) implements DomainEvent {}
