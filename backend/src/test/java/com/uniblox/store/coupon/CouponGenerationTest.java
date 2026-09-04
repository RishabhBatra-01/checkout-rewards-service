package com.uniblox.store.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CouponGenerationTest {

    private static final long PRODUCT = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CouponService couponService;
    @Autowired private RewardProperties rewards;

    /**
     * Milestones are counted from the global number of orders, so these tests need a
     * known starting point rather than whatever earlier test classes left behind.
     */
    @BeforeEach
    void startFromNoOrdersOrCoupons() {
        jdbc.update("delete from coupons");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
        jdbc.update("update products set inventory = 1000");
    }

    @Test
    void generatesNothingBeforeTheMilestoneIsReached() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval() - 1);

        mockMvc.perform(post("/api/admin/coupons/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Reward milestone not reached"))
                .andExpect(jsonPath("$.code").value("MILESTONE_NOT_REACHED"));

        assertThat(couponCount()).isZero();
    }

    @Test
    void generatesACouponOnceTheMilestoneIsReached() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval());

        mockMvc.perform(post("/api/admin/coupons/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.milestone").value(1))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(couponCount()).isEqualTo(1);
    }

    @Test
    void theCouponCarriesTheConfiguredDiscountPercentage() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval());

        mockMvc.perform(post("/api/admin/coupons/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountPercent").value(rewards.discountPercent()));

        assertThat(discountPercentOfOnlyCoupon()).isEqualTo(rewards.discountPercent());
    }

    @Test
    void aFailedCheckoutDoesNotCountTowardTheMilestone() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval() - 1);

        // One more checkout that cannot succeed: the cart wants stock that is not there.
        jdbc.update("update products set inventory = 0 where id = ?", PRODUCT);
        String doomedCart = cartWithOneItem();
        mockMvc.perform(checkout(doomedCart))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));
        jdbc.update("update products set inventory = 1000 where id = ?", PRODUCT);

        // The rolled-back checkout left no order, so the milestone is still short by one.
        assertThat(orderCount()).isEqualTo(rewards.orderInterval() - 1);
        mockMvc.perform(post("/api/admin/coupons/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MILESTONE_NOT_REACHED"));

        assertThat(couponCount()).isZero();
    }

    @Test
    void generatingAgainDoesNotProduceASecondCouponForTheSameMilestone() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval());

        String firstCouponId = JsonPath.read(
                mockMvc.perform(post("/api/admin/coupons/generate"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        // The next milestone needs another full interval of orders, so a repeat call is
        // refused rather than silently re-issuing the reward already granted.
        mockMvc.perform(post("/api/admin/coupons/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MILESTONE_NOT_REACHED"));

        assertThat(couponCount()).isEqualTo(1);
        assertThat(onlyCouponId()).isEqualTo(firstCouponId);
    }

    @Test
    void concurrentGenerationRequestsCreateAtMostOneCoupon() throws Exception {
        placeSuccessfulOrders(rewards.orderInterval());
        int administrators = 5;

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(administrators);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<CouponResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < administrators; i++) {
            attempts.add(pool.submit(() -> {
                startTogether.await();
                peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return couponService.generateNextReward();
                } finally {
                    inFlight.decrementAndGet();
                }
            }));
        }
        startTogether.countDown();

        List<CouponResponse> generated = new ArrayList<>();
        List<Throwable> refused = new ArrayList<>();
        for (Future<CouponResponse> attempt : attempts) {
            try {
                generated.add(attempt.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException failure) {
                refused.add(failure.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(peakInFlight.get())
                .as("the requests really did overlap; a sequential run would prove nothing")
                .isGreaterThan(1);
        assertThat(couponCount()).as("the milestone is rewarded exactly once").isEqualTo(1);
        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).milestone()).isEqualTo(1);
        assertThat(refused).hasSize(administrators - 1);
        // Losing the race is reported as a conflict, never as a duplicate coupon.
        assertThat(refused).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfAny(
                        MilestoneAlreadyRewardedException.class, MilestoneNotReachedException.class));
    }

    // ---------- helpers ----------

    private void placeSuccessfulOrders(int howMany) throws Exception {
        for (int i = 0; i < howMany; i++) {
            mockMvc.perform(checkout(cartWithOneItem())).andExpect(status().isCreated());
        }
        assertThat(orderCount()).isEqualTo(howMany);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            String cartId) {
        return post("/api/carts/{cartId}/checkout", cartId)
                .header("Idempotency-Key", "coupon-test-" + UUID.randomUUID());
    }

    private String cartWithOneItem() throws Exception {
        String cartId = JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":1}".formatted(PRODUCT)))
                .andExpect(status().isOk());
        return cartId;
    }

    private long orderCount() {
        return jdbc.queryForObject("select count(*) from orders", Long.class);
    }

    private long couponCount() {
        return jdbc.queryForObject("select count(*) from coupons", Long.class);
    }

    private int discountPercentOfOnlyCoupon() {
        return jdbc.queryForObject("select discount_percent from coupons", Integer.class);
    }

    private String onlyCouponId() {
        return jdbc.queryForObject("select id::text from coupons", String.class);
    }
}
