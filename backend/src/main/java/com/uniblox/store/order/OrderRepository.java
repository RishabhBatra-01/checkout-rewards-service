package com.uniblox.store.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Finds the order a given idempotency key already produced, if any. */
    @Query("select o from Order o left join fetch o.items where o.idempotencyKey = :key")
    Optional<Order> findByIdempotencyKey(@Param("key") String key);

    /** Loads the order with its lines, so it can be rendered outside a transaction. */
    @Query("select o from Order o left join fetch o.items where o.id = :orderId")
    Optional<Order> findByIdWithItems(@Param("orderId") UUID orderId);
}
