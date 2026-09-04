package com.uniblox.store.cart;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts")
class CartController {

    private final CartService cartService;

    CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping
    ResponseEntity<CartResponse> create() {
        CartResponse cart = cartService.create();
        return ResponseEntity.created(URI.create("/api/carts/" + cart.id())).body(cart);
    }

    @GetMapping("/{cartId}")
    CartResponse get(@PathVariable UUID cartId) {
        return cartService.view(cartId);
    }

    @PostMapping("/{cartId}/items")
    CartResponse addItem(@PathVariable UUID cartId, @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(cartId, request);
    }

    @PatchMapping("/{cartId}/items/{productId}")
    CartResponse updateItem(
            @PathVariable UUID cartId,
            @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItemQuantity(cartId, productId, request);
    }

    @DeleteMapping("/{cartId}/items/{productId}")
    CartResponse removeItem(@PathVariable UUID cartId, @PathVariable Long productId) {
        return cartService.removeItem(cartId, productId);
    }
}
