package com.uniblox.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class CheckoutApiIntegrationTest {

    private static final long PRODUCT_A = 1L;
    private static final long PRODUCT_B = 2L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;

    /**
     * Inventory and prices are global state shared by every test in this class, so
     * each test starts from a known catalogue rather than depending on test order.
     */
    @BeforeEach
    void resetCatalogue() {
        jdbc.update("update products set inventory = 100");
        jdbc.update(
                "update products set price_cents = 12999, name = 'Mechanical Keyboard' where id = ?",
                PRODUCT_A);
        jdbc.update(
                "update products set price_cents = 4550, name = 'Wireless Mouse' where id = ?",
                PRODUCT_B);
    }

    // ---------- checkout ----------

    @Test
    void checksOutACartAndReturnsThePricedOrder() throws Exception {
        String cartId = cartWith(PRODUCT_A, 2);

        mockMvc.perform(checkout(cartId, aKey()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/orders/")))
                .andExpect(jsonPath("$.cartId").value(cartId))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value((int) PRODUCT_A))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(12999))
                .andExpect(jsonPath("$.items[0].lineTotalCents").value(25998))
                .andExpect(jsonPath("$.grossTotalCents").value(25998))
                .andExpect(jsonPath("$.discountTotalCents").value(0))
                .andExpect(jsonPath("$.netTotalCents").value(25998));
    }

    @Test
    void rejectsCheckoutOfAnEmptyCart() throws Exception {
        String cartId = createCart();

        mockMvc.perform(checkout(cartId, aKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart is empty"))
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        assertThat(ordersForCart(cartId)).isZero();
    }

    @Test
    void rejectsCheckoutWhenInventoryIsInsufficient() throws Exception {
        jdbc.update("update products set inventory = 1 where id = ?", PRODUCT_A);
        String cartId = cartWith(PRODUCT_A, 2);

        mockMvc.perform(checkout(cartId, aKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient inventory"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(1);
    }

    @Test
    void reducesInventoryByTheQuantityPurchased() throws Exception {
        int before = inventoryOf(PRODUCT_A);
        String cartId = cartWith(PRODUCT_A, 3);

        mockMvc.perform(checkout(cartId, aKey())).andExpect(status().isCreated());

        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(before - 3);
    }

    @Test
    void marksTheCartCheckedOut() throws Exception {
        String cartId = cartWith(PRODUCT_A, 1);

        mockMvc.perform(checkout(cartId, aKey())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    void rejectsASecondCheckoutOfTheSameCartUnderADifferentKey() throws Exception {
        String cartId = cartWith(PRODUCT_A, 1);
        mockMvc.perform(checkout(cartId, aKey())).andExpect(status().isCreated());
        int afterFirst = inventoryOf(PRODUCT_A);

        // A different key is a genuinely new request, not a retry, so it is refused.
        mockMvc.perform(checkout(cartId, aKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart is not open"))
                .andExpect(jsonPath("$.code").value("CART_NOT_OPEN"));

        assertThat(ordersForCart(cartId)).isEqualTo(1);
        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(afterFirst);
    }

    @Test
    void orderLinesSnapshotTheNameAndPriceFromCheckoutTime() throws Exception {
        String cartId = cartWith(PRODUCT_A, 2);
        String orderId = checkoutReturningOrderId(cartId, aKey());

        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(12999));
    }

    @Test
    void repricingOrRenamingAProductDoesNotRewriteAnExistingOrder() throws Exception {
        String cartId = cartWith(PRODUCT_A, 2);
        String orderId = checkoutReturningOrderId(cartId, aKey());

        jdbc.update(
                "update products set price_cents = 99999, name = 'Renamed' where id = ?", PRODUCT_A);

        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(12999))
                .andExpect(jsonPath("$.items[0].lineTotalCents").value(25998))
                .andExpect(jsonPath("$.grossTotalCents").value(25998));
    }

    @Test
    void aFailedCheckoutLeavesNoOrderAndReturnsStockTakenForEarlierLines() throws Exception {
        // The first line has plenty of stock, the second does not. Stock for the first
        // is taken before the second fails, so only a rollback can restore it.
        jdbc.update("update products set inventory = 1 where id = ?", PRODUCT_B);
        String cartId = createCart();
        addItem(cartId, PRODUCT_A, 2);
        addItem(cartId, PRODUCT_B, 5);

        int inventoryA = inventoryOf(PRODUCT_A);
        int inventoryB = inventoryOf(PRODUCT_B);
        long orderItemsBefore = orderItemCount();

        mockMvc.perform(checkout(cartId, aKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        assertThat(ordersForCart(cartId)).isZero();
        assertThat(orderItemCount()).isEqualTo(orderItemsBefore);
        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(inventoryA);
        assertThat(inventoryOf(PRODUCT_B)).isEqualTo(inventoryB);

        // The cart is untouched too, so the customer can fix it and try again.
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // ---------- idempotency ----------

    @Test
    void retryingWithTheSameKeyReturnsTheOriginalOrderWithoutChangingAnything() throws Exception {
        String cartId = cartWith(PRODUCT_A, 2);
        String key = aKey();
        int inventoryBefore = inventoryOf(PRODUCT_A);

        String originalOrderId = checkoutReturningOrderId(cartId, key);
        int inventoryAfterFirst = inventoryOf(PRODUCT_A);

        mockMvc.perform(checkout(cartId, key))
                // A replay is not a creation, so it answers 200 and says so explicitly.
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(originalOrderId))
                .andExpect(jsonPath("$.grossTotalCents").value(25998));

        assertThat(inventoryAfterFirst).isEqualTo(inventoryBefore - 2);
        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(inventoryAfterFirst);
        assertThat(ordersForCart(cartId)).isEqualTo(1);
    }

    @Test
    void rejectsCheckoutWithNoIdempotencyKey() throws Exception {
        String cartId = cartWith(PRODUCT_A, 1);

        mockMvc.perform(post("/api/carts/{cartId}/checkout", cartId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Idempotency key required"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(ordersForCart(cartId)).isZero();
    }

    @Test
    void rejectsCheckoutWithABlankIdempotencyKey() throws Exception {
        String cartId = cartWith(PRODUCT_A, 1);

        mockMvc.perform(checkout(cartId, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(ordersForCart(cartId)).isZero();
    }

    @Test
    void rejectsAKeyAlreadyUsedForADifferentCart() throws Exception {
        String key = aKey();
        String firstCart = cartWith(PRODUCT_A, 1);
        mockMvc.perform(checkout(firstCart, key)).andExpect(status().isCreated());

        String secondCart = cartWith(PRODUCT_A, 1);
        mockMvc.perform(checkout(secondCart, key))
                // Returning the first cart's order here would answer a question the
                // client did not ask, so this is a conflict rather than a replay.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency key already used"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(ordersForCart(secondCart)).isZero();
        mockMvc.perform(get("/api/carts/{cartId}", secondCart))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void concurrentCheckoutsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        String cartId = cartWith(PRODUCT_A, 2);
        String key = aKey();
        int inventoryBefore = inventoryOf(PRODUCT_A);
        int attempts = 6;

        // Without these gauges the test would also pass if the attempts ran one after
        // another -- one creator and five replays is the correct sequential outcome too,
        // so it would prove the idempotency rule but nothing about contention.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<CheckoutResult>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return checkoutService.checkout(UUID.fromString(cartId), key);
                } finally {
                    inFlight.decrementAndGet();
                }
            }));
        }
        startTogether.countDown();

        List<CheckoutResult> succeeded = new ArrayList<>();
        List<Throwable> failed = new ArrayList<>();
        for (Future<CheckoutResult> future : futures) {
            try {
                succeeded.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException failure) {
                failed.add(failure.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(peakInFlight.get())
                .as("the attempts really did overlap; a sequential run would prove nothing")
                .isGreaterThan(1);

        // The invariant: one order, one inventory decrement, whatever the interleaving.
        assertThat(ordersForCart(cartId)).isEqualTo(1);
        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(inventoryBefore - 2);

        assertThat(failed).as("no attempt should error").isEmpty();
        assertThat(succeeded).hasSize(attempts);
        assertThat(succeeded).extracting(result -> result.order().id()).containsOnly(
                succeeded.get(0).order().id());
        assertThat(succeeded.stream().filter(result -> !result.replayed()).count())
                .as("exactly one caller created the order; the rest replayed it")
                .isEqualTo(1);
    }

    // ---------- helpers ----------

    private static String aKey() {
        return "checkout-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder checkout(String cartId, String idempotencyKey) {
        return post("/api/carts/{cartId}/checkout", cartId).header("Idempotency-Key", idempotencyKey);
    }

    private String checkoutReturningOrderId(String cartId, String key) throws Exception {
        return JsonPath.read(
                mockMvc.perform(checkout(cartId, key))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String createCart() throws Exception {
        return JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private void addItem(String cartId, long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":%d}".formatted(productId, quantity)))
                .andExpect(status().isOk());
    }

    private String cartWith(long productId, int quantity) throws Exception {
        String cartId = createCart();
        addItem(cartId, productId, quantity);
        return cartId;
    }

    private int inventoryOf(long productId) {
        return jdbc.queryForObject(
                "select inventory from products where id = ?", Integer.class, productId);
    }

    private long ordersForCart(String cartId) {
        return jdbc.queryForObject(
                "select count(*) from orders where cart_id = ?::uuid", Long.class, cartId);
    }

    private long orderItemCount() {
        return jdbc.queryForObject("select count(*) from order_items", Long.class);
    }
}
