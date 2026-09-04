package com.uniblox.store.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class AdminReportTest {

    private static final long KEYBOARD = 1L;
    private static final long MOUSE = 2L;
    private static final long KEYBOARD_PRICE = 12999L;
    private static final long MOUSE_PRICE = 4550L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    /** The report aggregates globally, so each test needs a known, empty starting point. */
    @BeforeEach
    void startFromNoOrdersOrCoupons() {
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
        jdbc.update("delete from coupons");
        jdbc.update("update products set inventory = 1000");
        jdbc.update(
                "update products set price_cents = ?, name = 'Mechanical Keyboard' where id = ?",
                KEYBOARD_PRICE, KEYBOARD);
        jdbc.update(
                "update products set price_cents = ?, name = 'Wireless Mouse' where id = ?",
                MOUSE_PRICE, MOUSE);
    }

    @Test
    void anEmptyStoreReportsZeroesRatherThanFailing() throws Exception {
        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulOrderCount").value(0))
                .andExpect(jsonPath("$.grossRevenueCents").value(0))
                .andExpect(jsonPath("$.totalDiscountsCents").value(0))
                .andExpect(jsonPath("$.netRevenueCents").value(0))
                .andExpect(jsonPath("$.purchasedQuantityByProduct").isEmpty())
                .andExpect(jsonPath("$.coupons.generated").value(0))
                .andExpect(jsonPath("$.coupons.available").value(0))
                .andExpect(jsonPath("$.coupons.redeemed").value(0));
    }

    @Test
    void multipleOrdersAggregateIntoOneSetOfTotals() throws Exception {
        checkout(cart(Map.of(KEYBOARD, 2)), null);          // 25998
        checkout(cart(Map.of(MOUSE, 3)), null);             // 13650
        checkout(cart(Map.of(KEYBOARD, 1, MOUSE, 1)), null); // 17549

        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulOrderCount").value(3))
                .andExpect(jsonPath("$.grossRevenueCents").value(57197))
                .andExpect(jsonPath("$.totalDiscountsCents").value(0))
                .andExpect(jsonPath("$.netRevenueCents").value(57197));
    }

    @Test
    void quantitiesAndRevenueAggregatePerProduct() throws Exception {
        checkout(cart(Map.of(KEYBOARD, 2)), null);
        checkout(cart(Map.of(KEYBOARD, 1, MOUSE, 4)), null);

        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasedQuantityByProduct.length()").value(2))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].productId").value((int) KEYBOARD))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].quantityPurchased").value(3))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].revenueCents").value(38997))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[1].productId").value((int) MOUSE))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[1].quantityPurchased").value(4))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[1].revenueCents").value(18200));
    }

    @Test
    void grossDiscountAndNetReconcileWithEachOtherAndWithTheProductLines() throws Exception {
        String code = availableCoupon(10);
        checkout(cart(Map.of(KEYBOARD, 2)), null);   // 25998, no discount
        checkout(cart(Map.of(MOUSE, 2)), code);      // 9100 gross, 910 off, 8190 net

        String body = mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long gross = ((Number) JsonPath.read(body, "$.grossRevenueCents")).longValue();
        long discounts = ((Number) JsonPath.read(body, "$.totalDiscountsCents")).longValue();
        long net = ((Number) JsonPath.read(body, "$.netRevenueCents")).longValue();
        List<Number> lineRevenue = JsonPath.read(body, "$.purchasedQuantityByProduct[*].revenueCents");

        assertThat(gross).isEqualTo(35098);
        assertThat(discounts).isEqualTo(910);
        assertThat(net).as("net = gross - discounts").isEqualTo(gross - discounts);
        assertThat(lineRevenue.stream().mapToLong(Number::longValue).sum())
                .as("per-product revenue sums to gross, so the two halves of the report agree")
                .isEqualTo(gross);
    }

    @Test
    void repricingAndRenamingAProductDoesNotChangeReportedHistory() throws Exception {
        checkout(cart(Map.of(KEYBOARD, 2)), null);

        jdbc.update(
                "update products set price_cents = 99999, name = 'Renamed Keyboard' where id = ?",
                KEYBOARD);

        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                // Both the money and the label come from the order snapshot, not the catalogue.
                .andExpect(jsonPath("$.grossRevenueCents").value(25998))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].revenueCents").value(25998));
    }

    @Test
    void couponCountsComeFromCouponState() throws Exception {
        String redeemed = availableCoupon(10);
        availableCoupon(20);
        availableCoupon(15);
        checkout(cart(Map.of(KEYBOARD, 1)), redeemed);

        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons.generated").value(3))
                .andExpect(jsonPath("$.coupons.available").value(2))
                .andExpect(jsonPath("$.coupons.redeemed").value(1))
                .andExpect(jsonPath("$.totalDiscountsCents").value(1300));
    }

    @Test
    void failedCheckoutsDoNotAppearInTheReport() throws Exception {
        checkout(cart(Map.of(KEYBOARD, 2)), null);
        String code = availableCoupon(10);

        // A checkout that cannot succeed: it wants more stock than exists.
        jdbc.update("update products set inventory = 1 where id = ?", KEYBOARD);
        String doomed = cart(Map.of(KEYBOARD, 50));
        mockMvc.perform(checkoutRequest(doomed, code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulOrderCount").value(1))
                .andExpect(jsonPath("$.grossRevenueCents").value(25998))
                .andExpect(jsonPath("$.totalDiscountsCents").value(0))
                .andExpect(jsonPath("$.purchasedQuantityByProduct[0].quantityPurchased").value(2))
                // The coupon the failed checkout tried to use is still unspent.
                .andExpect(jsonPath("$.coupons.available").value(1))
                .andExpect(jsonPath("$.coupons.redeemed").value(0));
    }

    @Test
    void theReportIsReadOnlyAndRepeatable() throws Exception {
        checkout(cart(Map.of(KEYBOARD, 2)), availableCoupon(10));
        Map<String, Long> before = rowCounts();

        String first = reportBody();
        String second = reportBody();

        assertThat(second).as("same data, same report").isEqualTo(first);
        assertThat(rowCounts()).as("reading the report changes nothing").isEqualTo(before);
    }

    // ---------- helpers ----------

    private Map<String, Long> rowCounts() {
        return Map.of(
                "products", count("products"),
                "carts", count("carts"),
                "cart_items", count("cart_items"),
                "orders", count("orders"),
                "order_items", count("order_items"),
                "coupons", count("coupons"),
                "redeemed_coupons",
                        jdbc.queryForObject(
                                "select count(*) from coupons where status = 'REDEEMED'", Long.class));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String reportBody() throws Exception {
        return mockMvc.perform(get("/api/admin/report"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String availableCoupon(int discountPercent) {
        String code = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        int milestone =
                jdbc.queryForObject("select coalesce(max(milestone), 0) + 1 from coupons", Integer.class);
        jdbc.update(
                "insert into coupons (id, code, milestone, discount_percent, status, created_at)"
                        + " values (gen_random_uuid(), ?, ?, ?, 'AVAILABLE', now())",
                code, milestone, discountPercent);
        return code;
    }

    private String cart(Map<Long, Integer> quantitiesByProduct) throws Exception {
        String cartId = JsonPath.read(
                mockMvc.perform(post("/api/carts"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        for (Map.Entry<Long, Integer> line : quantitiesByProduct.entrySet()) {
            mockMvc.perform(post("/api/carts/{cartId}/items", cartId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":%d,\"quantity\":%d}"
                                    .formatted(line.getKey(), line.getValue())))
                    .andExpect(status().isOk());
        }
        return cartId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            checkoutRequest(String cartId, String couponCode) {
        var request = post("/api/carts/{cartId}/checkout", cartId)
                .header("Idempotency-Key", "report-" + UUID.randomUUID());
        if (couponCode != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"couponCode\":\"%s\"}".formatted(couponCode));
        }
        return request;
    }

    private void checkout(String cartId, String couponCode) throws Exception {
        mockMvc.perform(checkoutRequest(cartId, couponCode)).andExpect(status().isCreated());
    }
}
