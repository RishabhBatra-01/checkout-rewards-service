package com.uniblox.store.coupon;

/**
 * The coupon exists but is no longer available -- already redeemed, or claimed by a
 * concurrent checkout a moment ago.
 */
public class CouponNotRedeemableException extends RuntimeException {

    public CouponNotRedeemableException(String code) {
        super("Coupon '" + code + "' has already been redeemed");
    }
}
