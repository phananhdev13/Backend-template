package com.acme.order.ordering.domain;

import com.acme.kernel.arch.ValueObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * An amount in a currency.
 *
 * <p>The pairing is the point. A bare {@code BigDecimal} lets a euro total be added to a sterling
 * one, and the result is a number that looks entirely reasonable. Here that addition throws.
 */
@ValueObject
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null || currency == null) {
            throw new IllegalArgumentException("Money needs both an amount and a currency");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + amount);
        }
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot combine %s with %s"
                    .formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
