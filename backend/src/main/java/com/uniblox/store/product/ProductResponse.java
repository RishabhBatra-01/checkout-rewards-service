package com.uniblox.store.product;

public record ProductResponse(Long id, String name, long priceCents, int inventory) {

    static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(), product.getName(), product.getPriceCents(), product.getInventory());
    }
}
