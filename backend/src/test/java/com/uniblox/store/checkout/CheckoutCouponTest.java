package com.uniblox.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import com.uniblox.store.coupon.CouponNotRedeemableException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckoutCouponTest {

    private static final long PRODUCT = 1L;
    private static final long UNIT_PRICE_CENTS = 12999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;

    @BeforeEach
    void resetCatalogue() {
        jdbc.update("update products set inventory = 1000");
        jdbc.update("update products set price_cents = ? where id = ?", UNIT_PRICE_CENTS, PRODUCT);
    }

    @Test
    void checkoutWithoutACouponIsUnchanged() throws Exception {
        String cartId = cartWith(2);

        mockMvc.perform(checkout(cartId, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossTotalCents").value(25998))
                .andExpect(jsonPath("$.discountTotalCents").value(0))
                .andExpect(jsonPath("$.netTotalCents").value(25998))
                .andExpect(jsonPath("$.couponCode").doesNotExist())
                .andExpect(jsonPath("$.couponDiscountPercent").doesNotExist());
    }

    @Test
    void aValidCouponAppliesItsDiscountAndIsMarkedRedeemed() throws Exception {
        String code = availableCoupon(10);
        String cartId = cartWith(2);

        mockMvc.perform(checkout(cartId, code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossTotalCents").value(25998))
                .andExpect(jsonPath("$.discountTotalCents").value(2600))
                .andExpect(jsonPath("$.netTotalCents").value(23398))
                .andExpect(jsonPath("$.couponCode").value(code))
                .andExpect(jsonPath("$.couponDiscountPercent").value(10));

        assertThat(statusOf(code)).isEqualTo("REDEEMED");
        assertThat(redeemedAtOf(code)).isNotNull();
    }

    @Test
    void theOrderPersistsGrossDiscountAndNetTotals() throws Exception {
        String code = availableCoupon(25);
        String cartId = cartWith(3);
        String orderId = orderIdFrom(checkout(cartId, code));

        // Read back from the database, not from the response that created it.
        assertThat(jdbc.queryForMap(
                        "select gross_total_cents, discount_total_cents, net_total_cents,"
                                + " coupon_code, coupon_discount_percent from orders where id = ?::uuid",
                        orderId))
                .containsEntry("gross_total_cents", 38997L)
                .containsEntry("discount_total_cents", 9749L)
                .containsEntry("net_total_cents", 29248L)
                .containsEntry("coupon_code", code)
                .containsEntry("coupon_discount_percent", 25);

        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(jsonPath("$.grossTotalCents").value(38997))
                .andExpect(jsonPath("$.discountTotalCents").value(9749))
                .andExpect(jsonPath("$.netTotalCents").value(29248));
    }

    @Test
    void anUnknownCouponIsRejected() throws Exception {
        String cartId = cartWith(1);

        mockMvc.perform(checkout(cartId, "NO-SUCH-COUPON"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Coupon not found"))
                .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));

        assertThat(ordersForCart(cartId)).isZero();
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void anAlreadyRedeemedCouponIsRejected() throws Exception {
        String code = availableCoupon(10);
        mockMvc.perform(checkout(cartWith(1), code)).andExpect(status().isCreated());

        String secondCart = cartWith(1);
        mockMvc.perform(checkout(secondCart, code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Coupon already redeemed"))
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_REDEEMED"));

        assertThat(ordersForCart(secondCart)).isZero();
    }

    @Test
    void aFailedCheckoutDoesNotConsumeTheCoupon() throws Exception {
        String code = availableCoupon(10);
        String cartId = cartWith(5);
        // The coupon is redeemed before stock is taken, so only a rollback can put it
        // back. Starve the product so the checkout fails after that point.
        jdbc.update("update products set inventory = 1 where id = ?", PRODUCT);

        mockMvc.perform(checkout(cartId, code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        assertThat(statusOf(code)).isEqualTo("AVAILABLE");
        assertThat(redeemedAtOf(code)).isNull();
        assertThat(ordersForCart(cartId)).isZero();

        // And it still works afterwards.
        jdbc.update("update products set inventory = 1000 where id = ?", PRODUCT);
        mockMvc.perform(checkout(cartWith(1), code)).andExpect(status().isCreated());
        assertThat(statusOf(code)).isEqualTo("REDEEMED");
    }

    @ParameterizedTest(name = "{0} cents at {1}% -> discount {2}, net {3}")
    @CsvSource({
        "1005, 10, 101, 904",   // 100.5 rounds half up to 101
        "1004, 10, 100, 904",   // 100.4 rounds down
        "1015, 10, 102, 913",   // 101.5 rounds half up to 102
        "333,  33, 110, 223",   // 109.89 rounds to 110
        "12999, 100, 12999, 0"  // a full discount lands exactly on zero, never below
    })
    void discountRoundingIsDeterministic(
            long priceCents, int percent, long expectedDiscount, long expectedNet) throws Exception {
        jdbc.update("update products set price_cents = ? where id = ?", priceCents, PRODUCT);
        String code = availableCoupon(percent);

        mockMvc.perform(checkout(cartWith(1), code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossTotalCents").value(priceCents))
                .andExpect(jsonPath("$.discountTotalCents").value(expectedDiscount))
                .andExpect(jsonPath("$.netTotalCents").value(expectedNet));
    }

    @Test
    void twoConcurrentCheckoutsCannotBothRedeemTheSameCoupon() throws Exception {
        String code = availableCoupon(10);
        int buyers = 5;
        List<UUID> cartIds = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            cartIds.add(UUID.fromString(cartWith(1)));
        }

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<CheckoutResult>> attempts = new ArrayList<>();
        for (UUID cartId : cartIds) {
            attempts.add(pool.submit(() -> {
                startTogether.await();
                peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return checkoutService.checkout(cartId, "coupon-race-" + cartId, code);
                } finally {
                    inFlight.decrementAndGet();
                }
            }));
        }
        startTogether.countDown();

        List<CheckoutResult> succeeded = new ArrayList<>();
        List<Throwable> failed = new ArrayList<>();
        for (Future<CheckoutResult> attempt : attempts) {
            try {
                succeeded.add(attempt.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException failure) {
                failed.add(failure.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(peakInFlight.get())
                .as("the checkouts really did overlap; a sequential run would prove nothing")
                .isGreaterThan(1);
        assertThat(succeeded).as("exactly one checkout redeems the coupon").hasSize(1);
        assertThat(succeeded.get(0).order().couponCode()).isEqualTo(code);
        assertThat(succeeded.get(0).order().discountTotalCents()).isEqualTo(1300);
        assertThat(failed).hasSize(buyers - 1);
        assertThat(failed).allSatisfy(
                failure -> assertThat(failure).isInstanceOf(CouponNotRedeemableException.class));

        assertThat(statusOf(code)).isEqualTo("REDEEMED");
        assertThat(ordersDiscountedBy(code)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private String availableCoupon(int discountPercent) {
        String code = "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        int milestone = jdbc.queryForObject(
                "select coalesce(max(milestone), 0) + 1 from coupons", Integer.class);
        jdbc.update(
                "insert into coupons (id, code, milestone, discount_percent, status, created_at)"
                        + " values (gen_random_uuid(), ?, ?, ?, 'AVAILABLE', now())",
                code, milestone, discountPercent);
        return code;
    }

    private MockHttpServletRequestBuilder checkout(String cartId, String couponCode) {
        MockHttpServletRequestBuilder request =
                post("/api/carts/{cartId}/checkout", cartId)
                        .header("Idempotency-Key", "coupon-" + UUID.randomUUID());
        if (couponCode != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"couponCode\":\"%s\"}".formatted(couponCode));
        }
        return request;
    }

    private String orderIdFrom(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String cartWith(int quantity) throws Exception {
        String cartId = JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":%d}".formatted(PRODUCT, quantity)))
                .andExpect(status().isOk());
        return cartId;
    }

    private String statusOf(String code) {
        return jdbc.queryForObject("select status from coupons where code = ?", String.class, code);
    }

    private Object redeemedAtOf(String code) {
        return jdbc.queryForObject(
                "select redeemed_at from coupons where code = ?", Object.class, code);
    }

    private long ordersForCart(String cartId) {
        return jdbc.queryForObject(
                "select count(*) from orders where cart_id = ?::uuid", Long.class, cartId);
    }

    private long ordersDiscountedBy(String code) {
        return jdbc.queryForObject(
                "select count(*) from orders where coupon_code = ?", Long.class, code);
    }
}
