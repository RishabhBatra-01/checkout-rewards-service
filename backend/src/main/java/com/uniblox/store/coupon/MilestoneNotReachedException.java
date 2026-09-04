package com.uniblox.store.coupon;

public class MilestoneNotReachedException extends RuntimeException {

    public MilestoneNotReachedException(int milestone, long ordersRequired, long successfulOrders) {
        super("Reward milestone %d needs %d successful orders; there are %d"
                .formatted(milestone, ordersRequired, successfulOrders));
    }
}
