package com.acme.order.ordering.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A stored order line. Reached only through {@link OrderEntity}, as in the domain. */
@Entity
@Table(name = "order_line")
public class OrderLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(name = "unit_price_currency", nullable = false, length = 3)
    private String unitPriceCurrency;

    protected OrderLineEntity() {
        // Required by JPA.
    }

    OrderLineEntity(String sku, int quantity, BigDecimal unitPriceAmount, String unitPriceCurrency) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPriceAmount = unitPriceAmount;
        this.unitPriceCurrency = unitPriceCurrency;
    }

    String sku() {
        return sku;
    }

    int quantity() {
        return quantity;
    }

    BigDecimal unitPriceAmount() {
        return unitPriceAmount;
    }

    String unitPriceCurrency() {
        return unitPriceCurrency;
    }
}
