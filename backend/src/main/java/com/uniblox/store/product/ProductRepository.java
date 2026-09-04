package com.uniblox.store.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Decrements inventory only if there is enough of it, in one statement.
     *
     * <p>The check and the decrement are the same operation, so no read can go stale
     * between them. PostgreSQL takes a row lock for the duration of the UPDATE, so
     * concurrent callers are serialised per product and the total can never go
     * negative.
     *
     * @return 1 if stock was taken, 0 if there was not enough
     */
    @Modifying
    @Query(
            value =
                    "update products set inventory = inventory - :quantity"
                            + " where id = :productId and inventory >= :quantity",
            nativeQuery = true)
    int decrementInventory(@Param("productId") Long productId, @Param("quantity") int quantity);
}
