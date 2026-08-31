package com.acme.messaging.fixture;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Instant;

/** A globally-ordered event, for tests asserting the single-partition rule. */
@EventContract(
        stream = "widgets.ledger-entry",
        partitionKey = "widgetId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.INFINITE,
        delivery = DeliveryGuarantee.EFFECTIVELY_ONCE,
        ordering = OrderingGuarantee.GLOBAL,
        schema = "contracts/events/widgets.ledger-entry.v1.json")
public record GlobalOrderedEvent(String widgetId, Instant occurredAt) implements DomainEvent {}
