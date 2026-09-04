package com.uniblox.store.coupon;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative operations. Everything under {@code /api/admin} is treated as
 * administrative; authentication is out of scope for this exercise, so the path is
 * the only thing marking the boundary.
 */
@RestController
@RequestMapping("/api/admin/coupons")
class CouponAdminController {

    private final CouponService couponService;

    CouponAdminController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    CouponResponse generate() {
        return couponService.generateNextReward();
    }
}
