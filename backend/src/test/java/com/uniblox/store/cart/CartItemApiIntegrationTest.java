package com.uniblox.store.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CartItemApiIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void addsAProductToTheCartAndReturnsPricedTotals() throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        long unitPriceCents = firstProductPriceCents();

        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(unitPriceCents))
                .andExpect(jsonPath("$.items[0].lineTotalCents").value(unitPriceCents * 2))
                .andExpect(jsonPath("$.subtotalCents").value(unitPriceCents * 2));
    }

    @Test
    void retrievingTheCartIncludesItsItems() throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":3}".formatted(productId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void addingTheSameProductAgainIncreasesQuantityInOneRow() throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        long unitPriceCents = firstProductPriceCents();

        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk());
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":3}".formatted(productId)))
                .andExpect(status().isOk())
                // Still one line, not two: the unique constraint is upheld by an increment.
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotalCents").value(unitPriceCents * 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantities(int quantity) throws Exception {
        String cartId = createCart();
        mockMvc.perform(addItem(cartId,
                        "{\"productId\":%d,\"quantity\":%d}".formatted(firstProductId(), quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("quantity must be greater than zero"));
    }

    @Test
    void returnsNotFoundForAnUnknownCart() throws Exception {
        mockMvc.perform(addItem(UUID.randomUUID().toString(),
                        "{\"productId\":%d,\"quantity\":1}".formatted(firstProductId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart not found"));
    }

    @Test
    void returnsNotFoundForAnUnknownProduct() throws Exception {
        mockMvc.perform(addItem(createCart(), "{\"productId\":999999,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void doesNotChangeInventoryWhenAddingToACart() throws Exception {
        int productId = firstProductId();
        int inventoryBefore = inventoryOf(productId);

        mockMvc.perform(addItem(createCart(), "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk());

        // A cart item is an intention to buy; stock is only claimed at checkout.
        assertThat(inventoryOf(productId)).isEqualTo(inventoryBefore);
    }

    // ---------- update quantity ----------

    @Test
    void updatesTheQuantityOfAnItemInTheCart() throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        long unitPriceCents = firstProductPriceCents();
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk());

        mockMvc.perform(updateItem(cartId, productId, "{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items.length()").value(1))
                // Replaces the quantity rather than adding to it: 2 updated to 5 is 5, not 7.
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotalCents").value(unitPriceCents * 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantitiesOnUpdate(int quantity) throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk());

        mockMvc.perform(updateItem(cartId, productId, "{\"quantity\":%d}".formatted(quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("quantity must be greater than zero"));

        // The rejected request left the existing quantity alone.
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void updatingAProductThatIsNotInTheCartReturnsNotFound() throws Exception {
        mockMvc.perform(updateItem(createCart(), firstProductId(), "{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart item not found"));
    }

    @Test
    void updatingAnItemInAnUnknownCartReturnsNotFound() throws Exception {
        mockMvc.perform(updateItem(UUID.randomUUID().toString(), firstProductId(), "{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart not found"));
    }

    // ---------- remove ----------

    @Test
    void removesAnItemFromTheCart() throws Exception {
        String cartId = createCart();
        int keptProductId = firstProductId();
        int removedProductId = secondProductId();
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":1}".formatted(keptProductId)))
                .andExpect(status().isOk());
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":4}".formatted(removedProductId)))
                .andExpect(status().isOk());

        mockMvc.perform(removeItem(cartId, removedProductId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keptProductId))
                .andExpect(jsonPath("$.subtotalCents").value(firstProductPriceCents()));

        // Gone for good, not just absent from that one response.
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keptProductId));
    }

    @Test
    void removingAProductThatIsNotInTheCartReturnsNotFound() throws Exception {
        mockMvc.perform(removeItem(createCart(), firstProductId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart item not found"));
    }

    @Test
    void removingAnItemFromAnUnknownCartReturnsNotFound() throws Exception {
        mockMvc.perform(removeItem(UUID.randomUUID().toString(), firstProductId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart not found"));
    }

    @Test
    void inventoryIsUnchangedByUpdatingAndRemovingItems() throws Exception {
        int productId = firstProductId();
        int inventoryBefore = inventoryOf(productId);
        String cartId = createCart();

        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(productId)))
                .andExpect(status().isOk());
        mockMvc.perform(updateItem(cartId, productId, "{\"quantity\":7}")).andExpect(status().isOk());
        mockMvc.perform(removeItem(cartId, productId)).andExpect(status().isOk());

        assertThat(inventoryOf(productId)).isEqualTo(inventoryBefore);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder updateItem(
            String cartId, int productId, String body) {
        return patch("/api/carts/{cartId}/items/{productId}", cartId, productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder removeItem(
            String cartId, int productId) {
        return delete("/api/carts/{cartId}/items/{productId}", cartId, productId);
    }

    private int secondProductId() throws Exception {
        return JsonPath.read(products(), "$[1].id");
    }

    // ---------- a checked-out cart is immutable ----------

    @Test
    void addingToACheckedOutCartIsRejected() throws Exception {
        String cartId = checkedOutCart();

        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":1}".formatted(secondProductId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart is not open"))
                .andExpect(jsonPath("$.code").value("CART_NOT_OPEN"));

        assertCartUnchangedAfterCheckout(cartId);
    }

    @Test
    void updatingAnItemInACheckedOutCartIsRejected() throws Exception {
        String cartId = checkedOutCart();

        mockMvc.perform(updateItem(cartId, firstProductId(), "{\"quantity\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_NOT_OPEN"));

        assertCartUnchangedAfterCheckout(cartId);
    }

    @Test
    void removingAnItemFromACheckedOutCartIsRejected() throws Exception {
        String cartId = checkedOutCart();

        mockMvc.perform(removeItem(cartId, firstProductId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_NOT_OPEN"));

        assertCartUnchangedAfterCheckout(cartId);
    }

    // ---------- quantity is bounded, so it cannot overflow ----------

    @ParameterizedTest
    @ValueSource(ints = {1001, Integer.MAX_VALUE})
    void rejectsASingleRequestAboveTheQuantityLimit(int quantity) throws Exception {
        String cartId = createCart();

        mockMvc.perform(addItem(cartId,
                        "{\"productId\":%d,\"quantity\":%d}".formatted(firstProductId(), quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void rejectsRepeatedAddsThatWouldExceedTheQuantityLimit() throws Exception {
        String cartId = createCart();
        int productId = firstProductId();
        // Two individually valid requests whose sum is not. Before the limit existed the
        // accumulated int could wrap negative and fail as an unhandled 500.
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":1000}".formatted(productId)))
                .andExpect(status().isOk());

        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":1}".formatted(productId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart item quantity limit exceeded"))
                .andExpect(jsonPath("$.code").value("CART_ITEM_QUANTITY_LIMIT"));

        // The rejected request left the stored quantity alone.
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(jsonPath("$.items[0].quantity").value(1000));
    }

    /** A cart that has been through checkout, so its status is CHECKED_OUT. */
    private String checkedOutCart() throws Exception {
        String cartId = createCart();
        mockMvc.perform(addItem(cartId, "{\"productId\":%d,\"quantity\":2}".formatted(firstProductId())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/carts/{cartId}/checkout", cartId)
                        .header("Idempotency-Key", "cart-immutability-" + UUID.randomUUID()))
                .andExpect(status().isCreated());
        return cartId;
    }

    private void assertCartUnchangedAfterCheckout(String cartId) throws Exception {
        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(firstProductId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addItem(
            String cartId, String body) {
        return post("/api/carts/{cartId}/items", cartId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String createCart() throws Exception {
        String body = mockMvc.perform(post("/api/carts"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String products() throws Exception {
        return mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private int firstProductId() throws Exception {
        return JsonPath.read(products(), "$[0].id");
    }

    private long firstProductPriceCents() throws Exception {
        return ((Number) JsonPath.read(products(), "$[0].priceCents")).longValue();
    }

    private int inventoryOf(int productId) throws Exception {
        List<Number> matches =
                JsonPath.read(products(), "$[?(@.id == %d)].inventory".formatted(productId));
        return matches.get(0).intValue();
    }
}
