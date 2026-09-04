package com.uniblox.store.cart;

/**
 * Lifecycle of a cart.
 *
 * <p>A cart is created {@link #OPEN} and may be modified while it stays that way.
 * Checkout moves it to {@link #CHECKED_OUT}, which is terminal: that is how a cart
 * is prevented from being checked out more than once.
 */
public enum CartStatus {
    OPEN,
    CHECKED_OUT
}
