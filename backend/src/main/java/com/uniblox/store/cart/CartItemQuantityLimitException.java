package com.uniblox.store.cart;

/**
 * Adding this quantity would push a cart line past the per-line maximum.
 *
 * <p>Distinct from a rejected request field: each request on its own is within range,
 * and only the combination with what is already in the cart exceeds the limit.
 */
public class CartItemQuantityLimitException extends RuntimeException {

    public CartItemQuantityLimitException(Long productId, long requestedTotal, int maximum) {
        super("Cart line for product %d would total %d units, which exceeds the maximum of %d"
                .formatted(productId, requestedTotal, maximum));
    }
}
