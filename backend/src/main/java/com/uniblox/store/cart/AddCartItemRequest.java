package com.uniblox.store.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull(message = "is required") Long productId,
        @NotNull(message = "is required") @Positive(message = "must be greater than zero")
                Integer quantity) {
}
