package com.uniblox.store.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    /**
     * The client-supplied key that produced this order. Unique across all orders, so
     * the database itself refuses to let one key create two orders.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /**
     * Lines are cascaded because an order and its lines are written once, together,
     * and a line has no meaning without its order. This differs from a cart, whose
     * items are added and removed independently and so are managed separately.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "gross_total_cents", nullable = false)
    private long grossTotalCents;

    @Column(name = "discount_total_cents", nullable = false)
    private long discountTotalCents;

    @Column(name = "net_total_cents", nullable = false)
    private long netTotalCents;

    /**
     * A copy of the coupon that was applied, not a reference to it. The order has to
     * explain its own discount after the coupon is redeemed, edited or removed -- the
     * same reason order lines copy product names and prices.
     */
    @Column(name = "coupon_code", updatable = false)
    private String couponCode;

    @Column(name = "coupon_discount_percent", updatable = false)
    private Integer couponDiscountPercent;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    protected Order() {
        // required by JPA
    }

    public static Order forCart(UUID cartId, String idempotencyKey) {
        Order order = new Order();
        order.cartId = cartId;
        order.idempotencyKey = idempotencyKey;
        order.placedAt = Instant.now();
        return order;
    }

    /** Adds a purchased line and keeps the stored totals consistent with it. */
    public void addLine(Long productId, String productName, long unitPriceCents, int quantity) {
        OrderItem item = OrderItem.of(this, productId, productName, unitPriceCents, quantity);
        items.add(item);
        grossTotalCents += item.getLineTotalCents();
        netTotalCents = grossTotalCents - discountTotalCents;
    }

    public UUID getId() {
        return id;
    }

    /**
     * Records the coupon that was applied and the money it took off.
     *
     * <p>Called after every line has been added, because the discount is a percentage
     * of the gross total. The discount is clamped to the gross so the net can never go
     * negative; the database enforces the same thing independently.
     */
    public void applyCoupon(String code, int discountPercent, long discountCents) {
        this.couponCode = code;
        this.couponDiscountPercent = discountPercent;
        this.discountTotalCents = Math.min(discountCents, grossTotalCents);
        this.netTotalCents = grossTotalCents - this.discountTotalCents;
    }

    public UUID getCartId() {
        return cartId;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public Integer getCouponDiscountPercent() {
        return couponDiscountPercent;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public long getGrossTotalCents() {
        return grossTotalCents;
    }

    public long getDiscountTotalCents() {
        return discountTotalCents;
    }

    public long getNetTotalCents() {
        return netTotalCents;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }
}
