package com.uniblox.store.cart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** Fetches the product alongside each item, so rendering a cart is a single query. */
    @Query("select i from CartItem i join fetch i.product where i.cartId = :cartId order by i.id")
    List<CartItem> findByCartId(@Param("cartId") UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, Long productId);
}
