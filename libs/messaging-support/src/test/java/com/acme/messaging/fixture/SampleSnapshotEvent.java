package com.acme.messaging.fixture;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.time.Instant;

/** A state-snapshot event, for tests exercising compaction. */
@EventContract(
        stream = "widgets.widget-state",
        partitionKey = "widgetId",
        payload = PayloadKind.STATE_SNAPSHOT,
        retention = StreamRetention.COMPACTED,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/widgets.widget-state.v1.json")
public record SampleSnapshotEvent(String widgetId, String status, Instant occurredAt) implements DomainEvent {}
