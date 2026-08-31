package com.acme.order.ordering.application;

import com.acme.kernel.arch.ReadModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order summaries, projected straight from the database.
 *
 * <p>This deliberately bypasses the aggregate. Hydrating {@code Order} objects and their lines to
 * render a list is work whose only output is discarded, and it turns a single indexed query into an
 * N+1. A read model is allowed to skip the domain precisely because it changes nothing - and
 * {@code ReadModelRules.readModelsHaveNoSideEffects} is what keeps that true as it is edited.
 *
 * <p>See {@code docs/principles/P-032-reads-and-writes-shaped-separately.md}.
 */
@ReadModel(id = "QRY-ORD-001")
@Transactional(readOnly = true)
public class OrderSummaryQuery {

    /** What a list screen needs, and no more. */
    public record OrderSummary(String orderId, String customerId, String status, BigDecimal subtotal) {}

    private final JdbcClient jdbc;

    public OrderSummaryQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderSummary> forCustomer(String customerId, int limit) {
        return jdbc.sql("""
                        select o.id as order_id, o.customer_id, o.status,
                               coalesce(sum(l.unit_price_amount * l.quantity), 0) as subtotal
                        from orders o
                        left join order_line l on l.order_id = o.id
                        where o.customer_id = :customerId
                        group by o.id, o.customer_id, o.status
                        order by o.placed_at desc
                        limit :limit
                        """)
                .param("customerId", customerId)
                .param("limit", limit)
                .query(OrderSummary.class)
                .list();
    }

    public Optional<OrderSummary> byId(String orderId) {
        return jdbc.sql("""
                        select o.id as order_id, o.customer_id, o.status,
                               coalesce(sum(l.unit_price_amount * l.quantity), 0) as subtotal
                        from orders o
                        left join order_line l on l.order_id = o.id
                        where o.id = :orderId
                        group by o.id, o.customer_id, o.status
                        """).param("orderId", orderId).query(OrderSummary.class).optional();
    }
}
