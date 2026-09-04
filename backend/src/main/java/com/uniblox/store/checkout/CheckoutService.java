package com.uniblox.store.checkout;

import com.uniblox.store.cart.Cart;
import com.uniblox.store.cart.CartItem;
import com.uniblox.store.cart.CartItemRepository;
import com.uniblox.store.cart.CartNotFoundException;
import com.uniblox.store.cart.CartNotOpenException;
import com.uniblox.store.cart.CartRepository;
import com.uniblox.store.cart.CartStatus;
import com.uniblox.store.cart.EmptyCartException;
import com.uniblox.store.coupon.Coupon;
import com.uniblox.store.coupon.CouponNotFoundException;
import com.uniblox.store.coupon.CouponNotRedeemableException;
import com.uniblox.store.coupon.CouponRepository;
import com.uniblox.store.order.Order;
import com.uniblox.store.order.OrderRepository;
import com.uniblox.store.order.OrderResponse;
import com.uniblox.store.product.InsufficientInventoryException;
import com.uniblox.store.product.Product;
import com.uniblox.store.product.ProductRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {

    private static final int MAX_KEY_LENGTH = 200;

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final CouponRepository coupons;

    CheckoutService(
            CartRepository carts,
            CartItemRepository cartItems,
            ProductRepository products,
            OrderRepository orders,
            CouponRepository coupons) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
        this.orders = orders;
        this.coupons = coupons;
    }

    /**
     * Turns an open cart into an order, at most once per idempotency key.
     *
     * <p>A client that retries because it never saw the response gets the original
     * order back rather than a second one. The guarantee is enforced by the database,
     * not by this method: the cart's row lock serialises concurrent attempts, and the
     * unique constraint on {@code orders.idempotency_key} makes a duplicate insert
     * impossible even if two attempts reach that point on different instances.
     */
    @Transactional
    public CheckoutResult checkout(UUID cartId, String idempotencyKey) {
        return checkout(cartId, idempotencyKey, null);
    }

    /** As above, optionally redeeming a coupon as part of the same transaction. */
    @Transactional
    public CheckoutResult checkout(UUID cartId, String idempotencyKey, String couponCode) {
        String key = requireUsableKey(idempotencyKey);

        // Taken before anything is read, so a concurrent checkout of this cart waits
        // here and then observes the committed result below rather than racing it.
        Cart cart = carts.findByIdForUpdate(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        Optional<Order> alreadyPlaced = orders.findByIdempotencyKey(key);
        if (alreadyPlaced.isPresent()) {
            Order order = alreadyPlaced.get();
            if (!order.getCartId().equals(cartId)) {
                throw new IdempotencyKeyReuseException(key, order.getCartId(), cartId);
            }
            if (!Objects.equals(order.getCouponCode(), couponCode)) {
                // The coupon is part of the request, so the same key asking for a
                // different one is a different request, not a retry.
                throw new IdempotencyKeyReuseException(key, order.getCouponCode(), couponCode);
            }
            return new CheckoutResult(OrderResponse.from(order), true);
        }

        if (cart.getStatus() != CartStatus.OPEN) {
            throw new CartNotOpenException(cartId, cart.getStatus());
        }

        List<CartItem> items = cartItems.findByCartId(cartId);
        if (items.isEmpty()) {
            throw new EmptyCartException(cartId);
        }

        // Redeemed before stock is taken, so that a later failure on inventory rolls the
        // coupon back to AVAILABLE -- the case the requirement is really about.
        Coupon coupon = redeemIfRequested(couponCode);

        // Stock is taken in ascending product id rather than cart order. Two carts
        // holding the same products in a different order would otherwise lock the
        // product rows in opposite sequences, which is the classic deadlock: each holds
        // what the other is waiting for. A single global ordering makes the cycle
        // impossible rather than merely unlikely.
        for (CartItem item : byAscendingProductId(items)) {
            takeStock(item.getProduct(), item.getQuantity());
        }

        // Lines keep the cart's own order, so what the customer sees is unaffected.
        Order order = Order.forCart(cartId, key);
        for (CartItem item : items) {
            Product product = item.getProduct();
            order.addLine(
                    product.getId(), product.getName(), product.getPriceCents(), item.getQuantity());
        }
        if (coupon != null) {
            order.applyCoupon(
                    coupon.getCode(),
                    coupon.getDiscountPercent(),
                    coupon.discountOn(order.getGrossTotalCents()));
        }
        cart.markCheckedOut();

        return new CheckoutResult(OrderResponse.from(persist(order, key, cartId)), false);
    }

    /**
     * Flushes the insert now rather than at commit, so a unique-key collision surfaces
     * here as a conflict the client can understand instead of an opaque commit failure.
     */
    private Order persist(Order order, String key, UUID cartId) {
        try {
            return orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException collision) {
            throw new IdempotencyKeyReuseException(key, cartId);
        }
    }

    /**
     * Claims the coupon, or returns null when none was supplied.
     *
     * <p>The row is read for its code and percentage, but the decision to allow the
     * redemption belongs entirely to the conditional update: the read could be stale
     * by the time the write happens, the update cannot be.
     */
    private Coupon redeemIfRequested(String couponCode) {
        if (couponCode == null) {
            return null;
        }
        Coupon coupon = coupons.findByCode(couponCode)
                .orElseThrow(() -> new CouponNotFoundException(couponCode));
        if (coupons.redeem(coupon.getId(), Instant.now()) == 0) {
            throw new CouponNotRedeemableException(couponCode);
        }
        return coupon;
    }

    private static List<CartItem> byAscendingProductId(List<CartItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
    }

    private void takeStock(Product product, int quantity) {
        if (products.decrementInventory(product.getId(), quantity) == 0) {
            throw new InsufficientInventoryException(
                    product.getId(), product.getName(), quantity, product.getInventory());
        }
    }

    private static String requireUsableKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("is required and must not be blank");
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new MissingIdempotencyKeyException(
                    "must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }
}
