package com.uniblox.store.coupon;

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
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    /** Which reward this is: 1 for the first earned, 2 for the second, and so on. */
    @Column(nullable = false, updatable = false)
    private int milestone;

    @Column(name = "discount_percent", nullable = false, updatable = false)
    private int discountPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    protected Coupon() {
        // required by JPA
    }

    static Coupon forMilestone(int milestone, int discountPercent, String code) {
        Coupon coupon = new Coupon();
        coupon.milestone = milestone;
        coupon.discountPercent = discountPercent;
        coupon.code = code;
        coupon.status = CouponStatus.AVAILABLE;
        coupon.createdAt = Instant.now();
        return coupon;
    }

    /**
     * The discount this coupon takes off a gross total, in whole cents.
     *
     * <p>Integer arithmetic throughout -- no floating point can enter, so the same
     * inputs always produce the same cent. Adding half the divisor before dividing
     * rounds half away from zero, so a discount of 100.5 cents becomes 101.
     *
     * <p>Clamped to the gross total so a total can never go negative, even though the
     * 1-100 range on the percentage already makes that unreachable.
     */
    public long discountOn(long grossTotalCents) {
        long roundedHalfUp = (grossTotalCents * discountPercent + 50) / 100;
        return Math.min(roundedHalfUp, grossTotalCents);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getMilestone() {
        return milestone;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }

    public CouponStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }
}
