package com.uniblox.store.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "carts")
public class Cart {

    /**
     * Identifiers are UUIDs because carts are addressed by clients. A sequential id
     * would let one client guess another's cart.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CartStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Cart() {
        // required by JPA
    }

    /** Creates a cart in the only state a new cart can legitimately start in. */
    public static Cart open() {
        Cart cart = new Cart();
        cart.status = CartStatus.OPEN;
        cart.createdAt = Instant.now();
        return cart;
    }

    /** Moves the cart to its terminal state. Only checkout should call this. */
    public void markCheckedOut() {
        this.status = CartStatus.CHECKED_OUT;
    }

    public UUID getId() {
        return id;
    }

    public CartStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
