package com.uniblox.store.cart;

import java.util.UUID;

/**
 * The cart exists, but does not contain the requested product.
 *
 * <p>Distinct from a product that does not exist at all: from the client's point of
 * view both are "there is nothing here to change", so both answer 404, but only this
 * one is about the contents of a cart.
 */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(UUID cartId, Long productId) {
        super("Cart " + cartId + " does not contain product " + productId);
    }
}
