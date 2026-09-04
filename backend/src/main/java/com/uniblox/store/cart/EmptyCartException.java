package com.uniblox.store.cart;

import java.util.UUID;

public class EmptyCartException extends RuntimeException {

    public EmptyCartException(UUID cartId) {
        super("Cart " + cartId + " is empty and cannot be checked out");
    }
}
