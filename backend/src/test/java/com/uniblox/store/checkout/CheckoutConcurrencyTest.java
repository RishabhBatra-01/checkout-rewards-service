package com.uniblox.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import com.uniblox.store.product.InsufficientInventoryException;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves checkout cannot oversell when separate customers race for the same stock.
 *
 * <p>This is the case the per-cart row lock does <em>not</em> cover: every buyer has
 * their own cart, so their cart locks never collide and all of them reach the
 * inventory update at once. The only thing standing between them and an oversell is
 * the conditional {@code UPDATE ... WHERE inventory >= :quantity}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckoutConcurrencyTest {

    private static final long SCARCE_PRODUCT = 5L;
    private static final int CONCURRENT_BUYERS = 5;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;

    @Test
    void onlyOneOfManyCartsCanBuyTheLastUnit() throws Exception {
        jdbc.update("update products set inventory = 1 where id = ?", SCARCE_PRODUCT);

        List<UUID> cartIds = new ArrayList<>();
        for (int buyer = 0; buyer < CONCURRENT_BUYERS; buyer++) {
            cartIds.add(UUID.fromString(cartWithOneUnitOfTheScarceProduct()));
        }

        // Without these gauges the test would also pass if the checkouts ran one after
        // another, which would prove nothing about contention. `peakInFlight` records how
        // many were genuinely inside checkout at the same moment.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<CheckoutResult>> attempts = new ArrayList<>();
        for (UUID cartId : cartIds) {
            attempts.add(pool.submit(() -> {
                startTogether.await();
                peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return checkoutService.checkout(cartId, "race-" + cartId);
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

        assertThat(succeeded).as("exactly one buyer gets the last unit").hasSize(1);
        assertThat(failed).as("everyone else is turned away").hasSize(CONCURRENT_BUYERS - 1);

        // Every loser failed for the right reason. This is also what rules out a
        // negative write: an inventory update that went below zero would be stopped by
        // the products_inventory_check constraint and would surface here as a
        // DataIntegrityViolationException, not as InsufficientInventoryException.
        assertThat(failed).allSatisfy(
                failure -> assertThat(failure).isInstanceOf(InsufficientInventoryException.class));

        assertThat(succeeded.get(0).order().items()).hasSize(1);
        assertThat(succeeded.get(0).order().items().get(0).quantity()).isEqualTo(1);
        assertThat(succeeded.get(0).replayed()).isFalse();

        assertThat(inventoryOf(SCARCE_PRODUCT)).as("the one unit was sold exactly once").isZero();
        assertThat(productsWithNegativeInventory()).isZero();
        assertThat(ordersFor(cartIds)).as("one cart produced an order, four did not").isEqualTo(1);
    }

    // ---------- helpers ----------

    private String cartWithOneUnitOfTheScarceProduct() throws Exception {
        String cartId = JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":1}".formatted(SCARCE_PRODUCT)))
                .andExpect(status().isOk());
        return cartId;
    }

    private int inventoryOf(long productId) {
        return jdbc.queryForObject(
                "select inventory from products where id = ?", Integer.class, productId);
    }

    private long productsWithNegativeInventory() {
        return jdbc.queryForObject(
                "select count(*) from products where inventory < 0", Long.class);
    }

    private long ordersFor(List<UUID> cartIds) {
        return cartIds.stream()
                .mapToLong(cartId -> jdbc.queryForObject(
                        "select count(*) from orders where cart_id = ?", Long.class, cartId))
                .sum();
    }
}
