package com.uniblox.store.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull(message = "is required") Long productId,
        @NotNull(message = "is required")
                @Positive(message = "must be greater than zero")
                @Max(value = CartItem.MAX_QUANTITY, message = "must not exceed 1000")
                Integer quantity) {
}
