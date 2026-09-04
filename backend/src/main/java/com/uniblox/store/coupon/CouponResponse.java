package com.uniblox.store.coupon;

import java.time.Instant;
import java.util.UUID;

public record CouponResponse(
        UUID id,
        String code,
        int milestone,
        int discountPercent,
        CouponStatus status,
        Instant createdAt) {

    static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getMilestone(),
                coupon.getDiscountPercent(),
                coupon.getStatus(),
                coupon.getCreatedAt());
    }
}
