package com.uniblox.store.cart;

import java.util.UUID;

/**
 * The cart exists but is no longer open.
 *
 * <p>Raised both when a checked-out cart is checked out again and when one is
 * modified, so the message describes the cart's state rather than the attempted
 * operation.
 */
public class CartNotOpenException extends RuntimeException {

    public CartNotOpenException(UUID cartId, CartStatus status) {
        super("Cart " + cartId + " is " + status + " and can no longer be modified or checked out");
    }
}
