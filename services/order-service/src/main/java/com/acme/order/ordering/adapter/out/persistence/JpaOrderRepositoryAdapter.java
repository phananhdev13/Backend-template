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

    /**
     * Inserts a never-before-seen order, or updates the row already tracking one.
     *
     * <p>An order that already has a row cannot be saved by constructing a fresh detached
     * {@code OrderEntity} and handing it to {@code save()}: a freshly constructed entity's
     * {@code @Version} defaults to {@code 0}, which only ever matches a row's actual version by
     * coincidence. On any second save, Hibernate would compare that {@code 0} against whatever the
     * row's version has advanced to, find no match, and report the update as a lost optimistic-lock
     * race that never happened. Loading the managed entity and mutating it in place instead means
     * Hibernate's own dirty checking issues the update against the version it already knows is
     * current.
     */
    @Override
    public void save(Order order) {
        List<OrderLineEntity> lines = toLineEntities(order);
        OrderEntity entity = jpa.findById(order.id().value())
                .map(existing -> {
                    existing.applyState(order.status(), lines);
                    return existing;
                })
                .orElseGet(() -> new OrderEntity(
                        order.id().value(), order.customerId().value(), order.status(), order.placedAt(), lines));
        jpa.save(entity);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpa.findById(id.value()).map(JpaOrderRepositoryAdapter::toDomain);
    }

    private static List<OrderLineEntity> toLineEntities(Order order) {
        return order.lines().stream()
                .map(line -> new OrderLineEntity(
                        line.sku(),
                        line.quantity(),
                        line.unitPrice().amount(),
                        line.unitPrice().currency().getCurrencyCode()))
                .toList();
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
