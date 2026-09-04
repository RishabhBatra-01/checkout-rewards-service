package com.uniblox.store.checkout;

import com.uniblox.store.order.OrderResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckoutController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CheckoutService checkoutService;

    CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * The header is declared optional so that a missing one and a blank one produce the
     * same error, rather than Spring rejecting one case and the service the other.
     */
    @PostMapping("/api/carts/{cartId}/checkout")
    ResponseEntity<OrderResponse> checkout(
            @PathVariable UUID cartId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) CheckoutRequest request) {

        CheckoutResult result =
                checkoutService.checkout(cartId, idempotencyKey, CheckoutRequest.couponCodeOf(request));
        OrderResponse order = result.order();

        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(order);
        }
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }
}
