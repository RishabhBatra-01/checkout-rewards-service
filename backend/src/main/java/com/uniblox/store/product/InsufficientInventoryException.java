package com.uniblox.store.product;

public class InsufficientInventoryException extends RuntimeException {

    public InsufficientInventoryException(
            Long productId, String productName, int requested, int available) {
        super("Product %d (%s) has %d in stock but %d were requested"
                .formatted(productId, productName, available, requested));
    }
}
