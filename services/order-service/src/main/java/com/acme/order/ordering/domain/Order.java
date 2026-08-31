package com.acme.order.ordering.domain;

import com.acme.kernel.arch.AggregateRoot;
import com.acme.kernel.error.BusinessRuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * An order and everything that must stay consistent with it.
 *
 * <p>The lines are inside the boundary: a line cannot be loaded, changed or reasoned about on its
 * own, because a change to one may invalidate the total, and nothing outside this class is allowed
 * to see the two disagree.
 *
 * <p>There are no setters. Every transition is a method named after what the business calls it, so
 * that the rules guarding it cannot be walked around - which is what a setter is, structurally.
 */
@AggregateRoot
public final class Order {

    private final OrderId id;
    private final CustomerId customerId;
    private final List<OrderLine> lines;
    private final Instant placedAt;
    private OrderStatus status;

    private Order(OrderId id, CustomerId customerId, List<OrderLine> lines, OrderStatus status, Instant placedAt) {
        this.id = id;
        this.customerId = customerId;
        this.lines = List.copyOf(lines);
        this.status = status;
        this.placedAt = placedAt;
    }

    /**
     * Places an order, or refuses to.
     *
     * <p>A factory rather than a constructor because placing an order is a business event with
     * rules, and a constructor that can be called with anything is a way to create an order that
     * broke them.
     */
    public static Order place(OrderId id, CustomerId customerId, List<OrderLine> lines, Clock clock) {
        if (lines.isEmpty()) {
            throw new BusinessRuleViolation("order.no-lines", "An order needs at least one line");
        }
        Currency currency = lines.getFirst().unitPrice().currency();
        boolean mixedCurrencies =
                lines.stream().anyMatch(line -> !line.unitPrice().currency().equals(currency));
        if (mixedCurrencies) {
            throw new BusinessRuleViolation(
                    "order.mixed-currencies",
                    "Every line on an order must be priced in the same currency",
                    Map.of("orderId", id.value()));
        }
        return new Order(id, customerId, lines, OrderStatus.PLACED, Instant.now(clock));
    }

    /** Rebuilds an order from storage without re-running the placement rules. */
    public static Order rehydrate(
            OrderId id, CustomerId customerId, List<OrderLine> lines, OrderStatus status, Instant placedAt) {
        return new Order(id, customerId, lines, status, placedAt);
    }

    /**
     * Cancels the order.
     *
     * <p>Cancelling an already-cancelled order is refused rather than ignored. Silently accepting
     * it would make a duplicate request indistinguishable from the first, and the caller has no way
     * to tell whether their second attempt did anything.
     */
    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessRuleViolation(
                    "order.already-cancelled",
                    "Order %s is already cancelled".formatted(id.value()),
                    Map.of("orderId", id.value()));
        }
        status = OrderStatus.CANCELLED;
    }

    /** The sum of every line, before any discount. */
    public Money subtotal() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money::plus)
                .orElseThrow(() -> new IllegalStateException("An order always has at least one line"));
    }

    /** The amount payable, after the policy has had its say. */
    public Money total(DiscountPolicy discountPolicy) {
        Money subtotal = subtotal();
        Money discount = discountPolicy.discountFor(subtotal);
        return new Money(subtotal.amount().subtract(discount.amount()), subtotal.currency());
    }

    public OrderId id() {
        return id;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant placedAt() {
        return placedAt;
    }
}
