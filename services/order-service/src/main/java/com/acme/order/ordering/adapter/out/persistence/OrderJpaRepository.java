package com.acme.order.ordering.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data's view of the orders table.
 *
 * <p>An implementation detail of this package. The application depends on
 * {@code OrderRepository}, the port, and never on this interface - which is what lets the storage
 * technology change without a use case noticing.
 */
interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {}
