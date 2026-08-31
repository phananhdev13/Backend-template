package com.acme.order.ordering.application.port.in;

import com.acme.kernel.arch.InputPort;
import com.acme.order.ordering.domain.OrderId;

/**
 * Placing an order.
 *
 * <p>One method. A second would be a second use case with its own rules, its own transaction and
 * its own authorisation, sharing a name with this one for no reason.
 */
@InputPort
public interface PlaceOrderUseCase {

    OrderId placeOrder(PlaceOrderCommand command);
}
