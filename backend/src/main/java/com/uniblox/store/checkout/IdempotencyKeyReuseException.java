package com.uniblox.store.checkout;

import java.util.UUID;

/**
 * The key has already been used, but for a different request.
 *
 * <p>Returning the original order would answer a question the client did not ask, so
 * this is a conflict rather than a replay.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    /** The owning order was already committed and could be read. */
    public IdempotencyKeyReuseException(String key, UUID usedForCartId, UUID requestedCartId) {
        super("Idempotency-Key '%s' was already used to check out cart %s, so it cannot be reused for cart %s"
                .formatted(key, usedForCartId, requestedCartId));
    }

    /** The key was used for this cart, but with a different coupon. */
    public IdempotencyKeyReuseException(String key, String originalCoupon, String requestedCoupon) {
        super("Idempotency-Key '%s' was already used to check out this cart with coupon %s, so it cannot be reused with coupon %s"
                .formatted(key, describe(originalCoupon), describe(requestedCoupon)));
    }

    private static String describe(String couponCode) {
        return couponCode == null ? "(none)" : "'" + couponCode + "'";
    }

    /**
     * A concurrent checkout claimed the key first. Its cart is not readable from this
     * transaction, which is being rolled back, so the message names only what is known.
     */
    public IdempotencyKeyReuseException(String key, UUID requestedCartId) {
        super("Idempotency-Key '%s' is already in use by another checkout, so it cannot be used for cart %s"
                .formatted(key, requestedCartId));
    }
}
