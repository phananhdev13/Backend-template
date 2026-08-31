package com.acme.order.ordering.domain;

import com.acme.kernel.arch.ValueObject;

/**
 * Identity of a customer.
 *
 * <p>A separate type from {@link OrderId} precisely so that passing one where the other is expected
 * does not compile. Both would be strings, and the compiler would have nothing to say about it.
 */
@ValueObject
public record CustomerId(String value) {

    public CustomerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A customer id cannot be blank");
        }
    }
}
