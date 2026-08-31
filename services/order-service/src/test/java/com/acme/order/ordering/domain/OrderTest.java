package com.acme.order.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.kernel.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules, tested where they are cheapest to enumerate: no Spring context, no database.
 *
 * <p>Re-testing any of this through HTTP would buy nothing and cost seconds per case.
 */
class OrderTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);

    private static OrderLine line(String sku, int quantity, String price, String currency) {
        return new OrderLine(sku, quantity, Money.of(price, currency));
    }

    @Test
    void placingAnOrderCapturesTheLinesAndTheTimeItHappened() {
        Order order = Order.place(
                OrderId.newId(), new CustomerId("cust-1"), List.of(line("SKU-1", 2, "10.00", "EUR")), FIXED);

        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.placedAt()).isEqualTo(Instant.parse("2026-08-30T10:00:00Z"));
        assertThat(order.subtotal()).isEqualTo(Money.of("20.00", "EUR"));
    }

    @Test
    void anOrderWithNoLinesIsRefused() {
        assertThatThrownBy(() -> Order.place(OrderId.newId(), new CustomerId("cust-1"), List.of(), FIXED))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("at least one line");
    }

    @Test
    void linesInDifferentCurrenciesAreRefusedRatherThanConverted() {
        List<OrderLine> mixed = List.of(line("SKU-1", 1, "10.00", "EUR"), line("SKU-2", 1, "10.00", "USD"));

        assertThatThrownBy(() -> Order.place(OrderId.newId(), new CustomerId("cust-1"), mixed, FIXED))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("same currency");
    }

    @Test
    void cancellingTwiceIsRefusedSoARepeatedRequestIsDistinguishable() {
        Order order = Order.place(
                OrderId.newId(), new CustomerId("cust-1"), List.of(line("SKU-1", 1, "10.00", "EUR")), FIXED);
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    void theDiscountAppliesOnlyAboveTheThreshold() {
        DiscountPolicy policy = new DiscountPolicy(Money.of("100.00", "EUR"), new BigDecimal("0.10"));

        Order small =
                Order.place(OrderId.newId(), new CustomerId("c"), List.of(line("SKU-1", 1, "50.00", "EUR")), FIXED);
        Order large =
                Order.place(OrderId.newId(), new CustomerId("c"), List.of(line("SKU-1", 4, "50.00", "EUR")), FIXED);

        assertThat(small.total(policy)).isEqualTo(Money.of("50.00", "EUR"));
        assertThat(large.total(policy)).isEqualTo(Money.of("180.00", "EUR"));
    }

    @Test
    void moneyInDifferentCurrenciesCannotBeAdded() {
        assertThatThrownBy(() -> Money.of("1.00", "EUR").plus(Money.of("1.00", "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
