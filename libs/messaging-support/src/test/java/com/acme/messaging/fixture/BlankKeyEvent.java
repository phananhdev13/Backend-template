package com.acme.messaging.fixture;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Instant;

/** Declares a partition key that is legitimately present but may be blank or null at runtime. */
@EventContract(
        stream = "widgets.widget-key-blanked",
        partitionKey = "widgetId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/widgets.widget-key-blanked.v1.json")
public record BlankKeyEvent(String widgetId, Instant occurredAt) implements DomainEvent {}
