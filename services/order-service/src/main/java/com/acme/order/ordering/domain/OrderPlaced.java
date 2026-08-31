package com.acme.order.ordering.domain;

import com.acme.kernel.event.DeliveryGuarantee;
import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.OrderingGuarantee;
import com.acme.kernel.event.PayloadKind;
import com.acme.kernel.event.StreamRetention;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order was placed.
 *
 * <p>A fact, in the past tense, carrying what a consumer needs rather than a pointer back into this
 * service's database. Note what the contract commits to and why:
 *
 * <ul>
 *   <li>{@code payload = FACT} - this states a change, so it can never be compacted. Compaction
 *       would delete superseded messages and leave a replaying consumer unable to rebuild anything.
 *   <li>{@code partitionKey = "orderId"} - everything about one order arrives in order, and the
 *       stream still scales across orders.
 *   <li>{@code AT_LEAST_ONCE} - the honest guarantee, which obliges every handler to be
 *       {@code @Idempotent}. The build enforces that pairing.
 * </ul>
 *
 * <p>The amount is a plain decimal plus a currency code rather than the domain's {@code Money},
 * because a consumer in another repository generates from the schema and has no access to our
 * types.
 */
@EventContract(
        stream = "orders.order-placed",
        version = 1,
        partitionKey = "orderId",
        payload = PayloadKind.FACT,
        retention = StreamRetention.TIME_WINDOW,
        retentionDays = 30,
        delivery = DeliveryGuarantee.AT_LEAST_ONCE,
        ordering = OrderingGuarantee.PER_KEY,
        schema = "contracts/events/orders.order-placed.v1.json")
public record OrderPlaced(
        String orderId, String customerId, BigDecimal totalAmount, String currency, Instant occurredAt)
        implements DomainEvent {}
