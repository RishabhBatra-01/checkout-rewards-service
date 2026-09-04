package com.uniblox.store.cart;

import java.util.UUID;

public class CartNotFoundException extends RuntimeException {

    public CartNotFoundException(UUID cartId) {
        super("Cart " + cartId + " was not found");
    }
}
