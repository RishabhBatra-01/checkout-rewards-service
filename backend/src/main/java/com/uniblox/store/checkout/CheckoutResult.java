package com.uniblox.store.checkout;

import com.uniblox.store.order.OrderResponse;

/**
 * The outcome of a checkout attempt.
 *
 * @param replayed true when the order already existed and was returned unchanged,
 *     which the API surfaces as 200 rather than 201
 */
public record CheckoutResult(OrderResponse order, boolean replayed) {
}
