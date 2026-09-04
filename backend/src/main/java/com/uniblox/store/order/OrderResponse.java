package com.uniblox.store.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID cartId,
        Instant placedAt,
        List<OrderItemResponse> items,
        long grossTotalCents,
        long discountTotalCents,
        long netTotalCents,
        String couponCode,
        Integer couponDiscountPercent) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCartId(),
                order.getPlacedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getGrossTotalCents(),
                order.getDiscountTotalCents(),
                order.getNetTotalCents(),
                order.getCouponCode(),
                order.getCouponDiscountPercent());
    }
}
