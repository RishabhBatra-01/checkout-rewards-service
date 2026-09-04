package com.uniblox.store.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateCartItemRequest(
        @NotNull(message = "is required") @Positive(message = "must be greater than zero")
                Integer quantity) {
}
