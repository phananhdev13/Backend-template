package com.acme.order.ordering.application.port.out;

import com.acme.kernel.arch.OutputPort;
import com.acme.order.ordering.domain.Order;
import com.acme.order.ordering.domain.OrderId;
import java.util.Optional;

/**
 * What the application needs from storage, said in the application's own words.
 *
 * <p>Owned by the layer that uses it, not by the one that implements it. Nothing here names a
 * table, a session or a driver, which is why the JPA adapter behind it can be replaced - or
 * substituted in a test with a map - without a single use case changing.
 */
@OutputPort
public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(OrderId id);
}
