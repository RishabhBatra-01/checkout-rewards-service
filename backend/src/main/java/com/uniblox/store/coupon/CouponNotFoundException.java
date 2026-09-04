package com.uniblox.store.coupon;

public class CouponNotFoundException extends RuntimeException {

    public CouponNotFoundException(String code) {
        super("Coupon '" + code + "' was not found");
    }
}
