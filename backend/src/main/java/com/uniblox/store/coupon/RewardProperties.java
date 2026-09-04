package com.uniblox.store.coupon;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The reward rule: every {@code orderInterval} successful orders earns one coupon
 * worth {@code discountPercent} off.
 *
 * <p>Configuration rather than data, because the rule is an operator decision and
 * there is no requirement to change it at runtime or to audit its history. The
 * consequence, recorded deliberately: coupons already generated keep the percentage
 * they were created with, but changing the interval later re-interprets which order
 * count a milestone corresponds to. Validated at startup, so a nonsensical value
 * fails the boot rather than the first request.
 */
@Validated
@ConfigurationProperties(prefix = "store.rewards")
public record RewardProperties(@Positive int orderInterval, @Min(1) @Max(100) int discountPercent) {
}
