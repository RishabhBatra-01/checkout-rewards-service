package com.uniblox.store.report;

import java.util.List;

public record ReportResponse(
        long successfulOrderCount,
        long grossRevenueCents,
        long totalDiscountsCents,
        long netRevenueCents,
        List<ProductSales> purchasedQuantityByProduct,
        CouponSummary coupons) {

    /**
     * @param name the name the product was most recently sold under, taken from the
     *     order lines rather than the catalogue
     */
    public record ProductSales(
            long productId, String name, long quantityPurchased, long revenueCents) {
    }

    public record CouponSummary(long generated, long available, long redeemed) {
    }
}
