package com.uniblox.store.order;

public record OrderItemResponse(
        Long productId, String name, long unitPriceCents, int quantity, long lineTotalCents) {

    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPriceCents(),
                item.getQuantity(),
                item.getLineTotalCents());
    }
}
