package com.uniblox.store.order;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderRepository orders;

    OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId) {
        return orders.findByIdWithItems(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
