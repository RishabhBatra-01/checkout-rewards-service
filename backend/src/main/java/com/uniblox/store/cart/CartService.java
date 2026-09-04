package com.uniblox.store.cart;

import com.uniblox.store.product.Product;
import com.uniblox.store.product.ProductNotFoundException;
import com.uniblox.store.product.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;

    CartService(CartRepository carts, CartItemRepository cartItems, ProductRepository products) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
    }

    @Transactional
    public CartResponse create() {
        return toResponse(carts.save(Cart.open()));
    }

    @Transactional(readOnly = true)
    public CartResponse view(UUID cartId) {
        return toResponse(carts.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId)));
    }

    /**
     * Adds a quantity of a product to a cart, or increases the quantity if the cart
     * already contains it.
     *
     * <p>Inventory is deliberately untouched: holding a product in a cart is not a
     * claim on stock. Availability is checked and inventory decremented at checkout.
     */
    @Transactional
    public CartResponse addItem(UUID cartId, AddCartItemRequest request) {
        Cart cart = carts.findByIdForUpdate(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        cartItems.findByCartIdAndProductId(cartId, product.getId())
                .ifPresentOrElse(
                        existing -> existing.increaseQuantityBy(request.quantity()),
                        () -> cartItems.save(CartItem.of(cartId, product, request.quantity())));

        return toResponse(cart);
    }

    /**
     * Replaces the quantity of a product already in the cart.
     *
     * <p>Unlike {@link #addItem}, this sets the quantity rather than adding to it, and
     * never creates a row: a product that is not in the cart is a 404, not an insert.
     */
    @Transactional
    public CartResponse updateItemQuantity(
            UUID cartId, Long productId, UpdateCartItemRequest request) {
        Cart cart = carts.findByIdForUpdate(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));
        CartItem item = cartItems.findByCartIdAndProductId(cartId, productId)
                .orElseThrow(() -> new CartItemNotFoundException(cartId, productId));

        item.changeQuantityTo(request.quantity());

        return toResponse(cart);
    }

    /** Removes a product from the cart. Inventory is untouched; nothing was ever held. */
    @Transactional
    public CartResponse removeItem(UUID cartId, Long productId) {
        Cart cart = carts.findByIdForUpdate(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));
        CartItem item = cartItems.findByCartIdAndProductId(cartId, productId)
                .orElseThrow(() -> new CartItemNotFoundException(cartId, productId));

        cartItems.delete(item);

        return toResponse(cart);
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cartItems.findByCartId(cart.getId()).stream()
                .map(CartItemResponse::from)
                .toList();
        long subtotalCents = items.stream().mapToLong(CartItemResponse::lineTotalCents).sum();
        return new CartResponse(
                cart.getId(), cart.getStatus(), cart.getCreatedAt(), items, subtotalCents);
    }
}
