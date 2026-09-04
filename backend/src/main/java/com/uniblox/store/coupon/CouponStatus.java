package com.uniblox.store.coupon;

/**
 * Lifecycle of a coupon.
 *
 * <p>A coupon is generated {@link #AVAILABLE} and becomes {@link #REDEEMED} when a
 * checkout consumes it. REDEEMED is terminal: the transition is made by a conditional
 * update, so exactly one checkout can ever make it.
 */
public enum CouponStatus {
    AVAILABLE,
    REDEEMED
}
