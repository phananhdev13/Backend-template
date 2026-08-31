package com.acme.order.ordering.adapter.out.persistence;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.order.ordering.application.port.out.OrderRepository;
import com.acme.order.ordering.domain.CustomerId;
import com.acme.order.ordering.domain.Money;
import com.acme.order.ordering.domain.Order;
import com.acme.order.ordering.domain.OrderId;
import com.acme.order.ordering.domain.OrderLine;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Satisfies {@link OrderRepository} with JPA, and owns the translation both ways.
 *
 * <p>Mapping lives here rather than in a separate mapper class because it is this adapter's whole
 * job: it is the seam between two models, and splitting it out only makes the seam harder to find.
 */
@OutboundAdapter(port = OrderRepository.class, kind = AdapterKind.PERSISTENCE)
public class JpaOrderRepositoryAdapter implements OrderRepository {

    private final OrderJpaRepository jpa;

    JpaOrderRepositoryAdapter(OrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Order order) {
        jpa.save(toEntity(order));
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpa.findById(id.value()).map(JpaOrderRepositoryAdapter::toDomain);
    }

    private static OrderEntity toEntity(Order order) {
        List<OrderLineEntity> lines = order.lines().stream()
                .map(line -> new OrderLineEntity(
                        line.sku(),
                        line.quantity(),
                        line.unitPrice().amount(),
                        line.unitPrice().currency().getCurrencyCode()))
                .toList();
        return new OrderEntity(order.id().value(), order.customerId().value(), order.status(), order.placedAt(), lines);
    }

    /**
     * Rebuilds the aggregate without re-running the placement rules.
     *
     * <p>Those rules applied when the order was placed. Re-applying them on load would make a row
     * that was legal yesterday unreadable today, the first time a rule tightens.
     */
    private static Order toDomain(OrderEntity entity) {
        List<OrderLine> lines = entity.lines().stream()
                .map(line -> new OrderLine(
                        line.sku(),
                        line.quantity(),
                        new Money(line.unitPriceAmount(), Currency.getInstance(line.unitPriceCurrency()))))
                .toList();
        return Order.rehydrate(
                new OrderId(entity.id()),
                new CustomerId(entity.customerId()),
                lines,
                entity.status(),
                entity.placedAt());
    }
}
