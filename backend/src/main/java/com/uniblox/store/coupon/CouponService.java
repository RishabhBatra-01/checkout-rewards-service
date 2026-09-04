package com.uniblox.store.coupon;

import com.uniblox.store.order.OrderRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

    private final CouponRepository coupons;
    private final OrderRepository orders;
    private final RewardProperties rewards;

    CouponService(CouponRepository coupons, OrderRepository orders, RewardProperties rewards) {
        this.coupons = coupons;
        this.orders = orders;
        this.rewards = rewards;
    }

    /**
     * Generates the coupon for the next unrewarded milestone, if enough successful
     * orders have been placed to have earned it.
     *
     * <p>Milestones are rewarded in order, one per call, so an administrator who is
     * several behind catches up by calling again rather than being handed a backlog at
     * once. Because they are awarded in sequence and never skipped, the next one is
     * simply the highest rewarded so far plus one.
     *
     * <p>Concurrency is settled by the unique constraint on {@code coupons.milestone},
     * not by the read below. Two requests can both decide milestone 3 is due; only one
     * insert survives, and the loser is told so.
     */
    @Transactional
    public CouponResponse generateNextReward() {
        // Only successful checkouts insert an order, and a failed one is rolled back,
        // so the row count is exactly the number of successfully placed orders.
        long successfulOrders = orders.count();

        int nextMilestone = coupons.findHighestRewardedMilestone().orElse(0) + 1;
        long ordersRequired = (long) nextMilestone * rewards.orderInterval();
        if (successfulOrders < ordersRequired) {
            throw new MilestoneNotReachedException(nextMilestone, ordersRequired, successfulOrders);
        }

        Coupon coupon =
                Coupon.forMilestone(nextMilestone, rewards.discountPercent(), newCouponCode());
        try {
            // Flushed here so a lost race surfaces as a conflict we can explain, rather
            // than as an opaque failure at commit time.
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException lostTheRace) {
            throw new MilestoneAlreadyRewardedException(nextMilestone);
        }
    }

    /**
     * A readable code with enough entropy that a collision is not a practical concern,
     * which keeps the constraint violation above unambiguously about the milestone.
     */
    private String newCouponCode() {
        String suffix =
                UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return "SAVE%d-%s".formatted(rewards.discountPercent(), suffix);
    }
}
