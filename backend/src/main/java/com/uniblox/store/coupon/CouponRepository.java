package com.uniblox.store.coupon;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** The highest milestone already rewarded, or empty when none has been. */
    @Query("select max(c.milestone) from Coupon c")
    Optional<Integer> findHighestRewardedMilestone();

    Optional<Coupon> findByCode(String code);

    /**
     * Marks a coupon redeemed only if it is still available, in one statement.
     *
     * <p>The check and the write are the same operation, so no read can go stale
     * between them. PostgreSQL locks the row for the duration of the UPDATE, so of two
     * concurrent checkouts holding the same code exactly one sees a row affected.
     *
     * @return 1 if this caller redeemed it, 0 if it was already redeemed
     */
    @Modifying
    @Query(
            value =
                    "update coupons set status = 'REDEEMED', redeemed_at = :redeemedAt"
                            + " where id = :couponId and status = 'AVAILABLE'",
            nativeQuery = true)
    int redeem(@Param("couponId") UUID couponId, @Param("redeemedAt") Instant redeemedAt);
}
