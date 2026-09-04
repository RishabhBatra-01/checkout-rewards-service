package com.uniblox.store.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long productId) {
        super("Product " + productId + " was not found");
    }
}
