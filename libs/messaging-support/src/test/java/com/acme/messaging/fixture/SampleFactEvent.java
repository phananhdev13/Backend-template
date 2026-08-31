package com.acme.messaging.fixture;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Instant;

/** A fact-shaped event, for tests that need a real {@code @EventContract} to read. */
@EventContract(
        stream = "widgets.widget-touched",
        partitionKey = "widgetId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        retentionDays = 14,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/widgets.widget-touched.v1.json")
public record SampleFactEvent(String widgetId, Instant occurredAt) implements DomainEvent {}
