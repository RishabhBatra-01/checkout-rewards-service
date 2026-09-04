package com.uniblox.store.cart;

import com.uniblox.store.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A product and quantity a customer intends to buy.
 *
 * <p>The item references the product rather than copying its name or price: while a
 * cart is open it reflects the live catalogue. Prices are only frozen when an order
 * is placed.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
        // required by JPA
    }

    static CartItem of(UUID cartId, Product product, int quantity) {
        CartItem item = new CartItem();
        item.cartId = cartId;
        item.product = product;
        item.quantity = quantity;
        return item;
    }

    void increaseQuantityBy(int amount) {
        this.quantity += amount;
    }

    void changeQuantityTo(int newQuantity) {
        this.quantity = newQuantity;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }
}
