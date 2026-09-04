package com.uniblox.store.cart;

import java.util.UUID;

/** The cart exists but has already been checked out, so it cannot be checked out again. */
public class CartNotOpenException extends RuntimeException {

    public CartNotOpenException(UUID cartId, CartStatus status) {
        super("Cart " + cartId + " is " + status + " and cannot be checked out");
    }
}
