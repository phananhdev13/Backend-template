package com.acme.messaging.fixture;

import com.acme.kernel.event.DomainEvent;
import java.time.Instant;

/** Deliberately carries no {@code @EventContract}, for negative tests. */
public record UnannotatedEvent(String widgetId, Instant occurredAt) implements DomainEvent {}
