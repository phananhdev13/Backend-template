package com.acme.order.ordering.domain;

import com.acme.kernel.arch.ValueObject;

/** One product and how many of it, priced at the moment the order was placed. */
@ValueObject
public record OrderLine(String sku, int quantity, Money unitPrice) {

    public OrderLine {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("An order line needs a sku");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("An order line needs a positive quantity, got " + quantity);
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("An order line needs a unit price");
        }
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
