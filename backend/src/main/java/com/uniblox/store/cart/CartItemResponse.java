package com.uniblox.store.cart;

import com.uniblox.store.product.Product;

public record CartItemResponse(
        Long productId, String name, long unitPriceCents, int quantity, long lineTotalCents) {

    static CartItemResponse from(CartItem item) {
        Product product = item.getProduct();
        return new CartItemResponse(
                product.getId(),
                product.getName(),
                product.getPriceCents(),
                item.getQuantity(),
                // Integer cents multiplied by an integer quantity: exact, no rounding.
                product.getPriceCents() * item.getQuantity());
    }
}
