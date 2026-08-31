package com.acme.order.ordering.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The wire format for placing an order.
 *
 * <p>Separate from the domain model on purpose: this shape is a promise to clients and changes when
 * the API changes, while {@code Order} changes when the business does. Fusing them makes every
 * refactor a breaking API change.
 */
public record PlaceOrderRequest(
        @NotBlank String customerId,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotBlank String sku,
            @Positive int quantity,
            @NotBlank String unitPrice) {}
}
