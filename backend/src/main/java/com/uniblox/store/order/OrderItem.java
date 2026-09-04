package com.uniblox.store.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One purchased line, frozen at the moment of checkout.
 *
 * <p>The product is held as a plain id rather than an association: this row must be
 * readable and correct without consulting the catalogue, because the catalogue is
 * free to change afterwards.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;

    protected OrderItem() {
        // required by JPA
    }

    static OrderItem of(
            Order order, Long productId, String productName, long unitPriceCents, int quantity) {
        OrderItem item = new OrderItem();
        item.order = order;
        item.productId = productId;
        item.productName = productName;
        item.unitPriceCents = unitPriceCents;
        item.quantity = quantity;
        // Integer cents times an integer quantity: exact, and stored so the charged
        // amount can never be recomputed into something different.
        item.lineTotalCents = unitPriceCents * quantity;
        return item;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getLineTotalCents() {
        return lineTotalCents;
    }
}
