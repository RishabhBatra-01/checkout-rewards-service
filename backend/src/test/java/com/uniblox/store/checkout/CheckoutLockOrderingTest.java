package com.uniblox.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concurrent checkouts of carts holding the same products in opposite order.
 *
 * <p>Cart-item order is per cart, so without a global ordering one checkout would lock
 * product A then B while another locks B then A -- each holding what the other waits
 * for. PostgreSQL detects that and aborts one transaction, which would surface to the
 * caller as a failure rather than a sale. Taking stock in ascending product id removes
 * the cycle.
 *
 * <p>Deadlocks are probabilistic, so this runs several rounds of overlapping pairs to
 * give one a real chance to occur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckoutLockOrderingTest {

    private static final long PRODUCT_A = 1L;
    private static final long PRODUCT_B = 2L;
    private static final int ROUNDS = 10;
    private static final int CHECKOUTS_PER_ROUND = 6;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;

    @Test
    void cartsHoldingTheSameProductsInOppositeOrderDoNotDeadlock() throws Exception {
        jdbc.update("update products set inventory = 1000 where id in (?, ?)", PRODUCT_A, PRODUCT_B);
        int inventoryABefore = inventoryOf(PRODUCT_A);
        int inventoryBBefore = inventoryOf(PRODUCT_B);

        List<Throwable> failures = new ArrayList<>();
        int totalCheckouts = 0;

        for (int round = 0; round < ROUNDS; round++) {
            // Half the carts list A before B, the other half B before A.
            List<UUID> cartIds = new ArrayList<>();
            for (int i = 0; i < CHECKOUTS_PER_ROUND; i++) {
                cartIds.add(i % 2 == 0
                        ? UUID.fromString(cartWith(PRODUCT_A, PRODUCT_B))
                        : UUID.fromString(cartWith(PRODUCT_B, PRODUCT_A)));
            }

            ExecutorService pool = Executors.newFixedThreadPool(CHECKOUTS_PER_ROUND);
            CountDownLatch startTogether = new CountDownLatch(1);
            List<Future<CheckoutResult>> attempts = new ArrayList<>();
            for (UUID cartId : cartIds) {
                attempts.add(pool.submit(() -> {
                    startTogether.await();
                    return checkoutService.checkout(cartId, "lock-order-" + cartId);
                }));
            }
            startTogether.countDown();

            for (Future<CheckoutResult> attempt : attempts) {
                try {
                    attempt.get(30, TimeUnit.SECONDS);
                    totalCheckouts++;
                } catch (ExecutionException failure) {
                    failures.add(failure.getCause());
                }
            }
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("no checkout should fail; a deadlock would abort one of the pair")
                .isEmpty();
        assertThat(totalCheckouts).isEqualTo(ROUNDS * CHECKOUTS_PER_ROUND);

        // Every checkout took exactly one of each product, and none was lost or double-counted.
        assertThat(inventoryOf(PRODUCT_A)).isEqualTo(inventoryABefore - totalCheckouts);
        assertThat(inventoryOf(PRODUCT_B)).isEqualTo(inventoryBBefore - totalCheckouts);
    }

    private String cartWith(long firstProduct, long secondProduct) throws Exception {
        String cartId = JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        addItem(cartId, firstProduct);
        addItem(cartId, secondProduct);
        return cartId;
    }

    private void addItem(String cartId, long productId) throws Exception {
        mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d,\"quantity\":1}".formatted(productId)))
                .andExpect(status().isOk());
    }

    private int inventoryOf(long productId) {
        return jdbc.queryForObject(
                "select inventory from products where id = ?", Integer.class, productId);
    }
}
