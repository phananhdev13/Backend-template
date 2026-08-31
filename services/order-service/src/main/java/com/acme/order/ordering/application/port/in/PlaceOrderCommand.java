package com.acme.order.ordering.application.port.in;

import com.acme.kernel.arch.Command;
import com.acme.order.ordering.domain.CustomerId;
import com.acme.order.ordering.domain.OrderLine;
import java.util.List;

/**
 * What a caller must supply to place an order.
 *
 * <p>Domain types, not the strings and decimals they arrived as. Parsing happens in the inbound
 * adapter, so by the time this record exists an unparseable currency or a negative quantity is
 * already impossible - and the use case has no validation of its own to forget.
 */
@Command
public record PlaceOrderCommand(CustomerId customerId, List<OrderLine> lines) {

    public PlaceOrderCommand {
        lines = List.copyOf(lines);
    }
}
