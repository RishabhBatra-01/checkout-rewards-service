package com.uniblox.store.checkout;

public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException(String reason) {
        super("Idempotency-Key header " + reason);
    }
}
