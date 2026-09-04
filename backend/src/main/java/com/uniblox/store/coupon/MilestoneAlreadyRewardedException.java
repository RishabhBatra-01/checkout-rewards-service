package com.uniblox.store.coupon;

/**
 * Another request generated this milestone's coupon first.
 *
 * <p>Only reachable when two generation requests race: sequentially, the second call
 * sees the coupon and reports the next milestone as unreached instead.
 */
public class MilestoneAlreadyRewardedException extends RuntimeException {

    public MilestoneAlreadyRewardedException(int milestone) {
        super("Reward milestone " + milestone + " has already been rewarded with a coupon");
    }
}
