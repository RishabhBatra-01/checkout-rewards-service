package com.uniblox.store.cart;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * Loads a cart with a row lock (SELECT ... FOR UPDATE).
     *
     * <p>Used by operations that modify the cart's contents. Because every mutation
     * takes the same lock, changes to one cart are serialised: two requests adding
     * the same product cannot both find "no existing row" and both insert.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.id = :cartId")
    Optional<Cart> findByIdForUpdate(@Param("cartId") UUID cartId);
}
