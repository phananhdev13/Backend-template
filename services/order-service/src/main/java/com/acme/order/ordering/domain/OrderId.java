package com.acme.order.ordering.domain;

import com.acme.kernel.arch.ValueObject;
import java.util.UUID;

/** Identity of an order, distinct from every other identifier in the system. */
@ValueObject
public record OrderId(String value) {

    public OrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An order id cannot be blank");
        }
    }

    public static OrderId newId() {
        return new OrderId(UUID.randomUUID().toString());
    }
}
