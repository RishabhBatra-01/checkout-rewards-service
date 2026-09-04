package com.uniblox.store.checkout;

/**
 * Optional checkout body. Absent entirely, or with a null or blank code, means "no
 * coupon" and checkout behaves exactly as it did before coupons existed.
 */
public record CheckoutRequest(String couponCode) {

    static String couponCodeOf(CheckoutRequest request) {
        if (request == null || request.couponCode() == null || request.couponCode().isBlank()) {
            return null;
        }
        return request.couponCode().trim();
    }
}
