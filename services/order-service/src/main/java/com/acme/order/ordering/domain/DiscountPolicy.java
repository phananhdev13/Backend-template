package com.acme.order.ordering.domain;

import com.acme.kernel.arch.DomainPolicy;
import java.math.BigDecimal;

/**
 * Whether an order qualifies for the large-order discount, and how much.
 *
 * <p>Separated from {@link Order} because it changes on a different clock: the threshold and the
 * rate are commercial decisions revisited every quarter, while what an order *is* changes rarely.
 * Fused into the aggregate, every pricing experiment would be a change to the order model.
 */
@DomainPolicy(decides = "the discount an order qualifies for")
public final class DiscountPolicy {

    private final Money threshold;
    private final BigDecimal rate;

    public DiscountPolicy(Money threshold, BigDecimal rate) {
        this.threshold = threshold;
        this.rate = rate;
    }

    /** The discount to apply, which is zero when the order does not qualify. */
    public Money discountFor(Money subtotal) {
        if (!subtotal.isGreaterThan(threshold)) {
            return Money.zero(subtotal.currency());
        }
        return new Money(subtotal.amount().multiply(rate), subtotal.currency());
    }
}
