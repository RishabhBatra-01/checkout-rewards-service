package com.uniblox.store.web;

import com.uniblox.store.cart.CartItemNotFoundException;
import com.uniblox.store.cart.CartNotFoundException;
import com.uniblox.store.cart.CartNotOpenException;
import com.uniblox.store.cart.EmptyCartException;
import com.uniblox.store.checkout.IdempotencyKeyReuseException;
import com.uniblox.store.checkout.MissingIdempotencyKeyException;
import com.uniblox.store.coupon.CouponNotFoundException;
import com.uniblox.store.coupon.CouponNotRedeemableException;
import com.uniblox.store.coupon.MilestoneAlreadyRewardedException;
import com.uniblox.store.coupon.MilestoneNotReachedException;
import com.uniblox.store.order.OrderNotFoundException;
import com.uniblox.store.product.InsufficientInventoryException;
import com.uniblox.store.product.ProductNotFoundException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain errors into RFC 7807 problem responses.
 *
 * <p>Each response carries a stable {@code code}, so a client can branch on the exact
 * failure rather than on the status alone -- several distinct conditions here are
 * legitimately 409.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(CartNotFoundException.class)
    ProblemDetail handleCartNotFound(CartNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Cart not found", "CART_NOT_FOUND", exception);
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    ProblemDetail handleCartItemNotFound(CartItemNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND, "Cart item not found", "CART_ITEM_NOT_FOUND", exception);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleProductNotFound(ProductNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Product not found", "PRODUCT_NOT_FOUND", exception);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", "ORDER_NOT_FOUND", exception);
    }

    @ExceptionHandler(CartNotOpenException.class)
    ProblemDetail handleCartNotOpen(CartNotOpenException exception) {
        return problem(HttpStatus.CONFLICT, "Cart is not open", "CART_NOT_OPEN", exception);
    }

    @ExceptionHandler(EmptyCartException.class)
    ProblemDetail handleEmptyCart(EmptyCartException exception) {
        return problem(HttpStatus.CONFLICT, "Cart is empty", "CART_EMPTY", exception);
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    ProblemDetail handleInsufficientInventory(InsufficientInventoryException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Insufficient inventory",
                "INSUFFICIENT_INVENTORY",
                exception);
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    ProblemDetail handleMissingIdempotencyKey(MissingIdempotencyKeyException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Idempotency key required",
                "IDEMPOTENCY_KEY_REQUIRED",
                exception);
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail handleIdempotencyKeyReuse(IdempotencyKeyReuseException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Idempotency key already used",
                "IDEMPOTENCY_KEY_REUSED",
                exception);
    }

    @ExceptionHandler(CouponNotFoundException.class)
    ProblemDetail handleCouponNotFound(CouponNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Coupon not found", "COUPON_NOT_FOUND", exception);
    }

    @ExceptionHandler(CouponNotRedeemableException.class)
    ProblemDetail handleCouponNotRedeemable(CouponNotRedeemableException exception) {
        return problem(
                HttpStatus.CONFLICT, "Coupon already redeemed", "COUPON_ALREADY_REDEEMED", exception);
    }

    @ExceptionHandler(MilestoneNotReachedException.class)
    ProblemDetail handleMilestoneNotReached(MilestoneNotReachedException exception) {
        return problem(
                HttpStatus.CONFLICT, "Reward milestone not reached", "MILESTONE_NOT_REACHED", exception);
    }

    @ExceptionHandler(MilestoneAlreadyRewardedException.class)
    ProblemDetail handleMilestoneAlreadyRewarded(MilestoneAlreadyRewardedException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Reward milestone already rewarded",
                "MILESTONE_ALREADY_REWARDED",
                exception);
    }

    /** Reports which fields were rejected, so a client can fix the request without guessing. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationFailure(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty("code", "VALIDATION_FAILED");
        return problem;
    }

    private static ProblemDetail problem(
            HttpStatus status, String title, String code, RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
