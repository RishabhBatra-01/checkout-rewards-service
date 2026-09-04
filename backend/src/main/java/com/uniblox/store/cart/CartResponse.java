package com.uniblox.store.cart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        CartStatus status,
        Instant createdAt,
        List<CartItemResponse> items,
        long subtotalCents) {
}
