package com.acme.order.ordering.adapter.out.persistence;

import com.acme.order.ordering.domain.OrderStatus;
import com.acme.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * How an order is stored, which is not how an order is modelled.
 *
 * <p>Kept separate from {@code Order} because the two change for different reasons: this one for
 * column types, indexes and query plans, the other for business rules. A single class serving both
 * makes every schema decision a domain decision, and puts JPA's no-argument constructor and mutable
 * fields inside the aggregate that exists to forbid exactly that.
 */
@Entity
@Table(name = "orders")
public class OrderEntity extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderLineEntity> lines = new ArrayList<>();

    protected OrderEntity() {
        // Required by JPA.
    }

    OrderEntity(String id, String customerId, OrderStatus status, Instant placedAt, List<OrderLineEntity> lines) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.placedAt = placedAt;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * Replaces this managed entity's state in place, so the update Hibernate issues carries this
     * row's own tracked {@code @Version} rather than the {@code 0} a freshly constructed entity
     * would default to. See {@code JpaOrderRepositoryAdapter.save} for why that distinction is the
     * difference between an update and an optimistic-lock failure on every second save.
     */
    void applyState(OrderStatus status, List<OrderLineEntity> lines) {
        this.status = status;
        this.lines.clear();
        this.lines.addAll(lines);
    }

    String id() {
        return id;
    }

    String customerId() {
        return customerId;
    }

    OrderStatus status() {
        return status;
    }

    Instant placedAt() {
        return placedAt;
    }

    List<OrderLineEntity> lines() {
        return lines;
    }
}
