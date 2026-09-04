/**
 * There is no authentication, so the cart id in localStorage *is* the customer's
 * identity. Every access is guarded: localStorage throws in private windows and when
 * a browser blocks site data, and a storage failure must never break the page.
 */
const CART_ID = "store.cartId";
const LAST_ORDER_ID = "store.lastOrderId";
const LAST_COUPON_CODE = "store.lastCouponCode";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // Storage unavailable; the page still works for the current view.
  }
}

export const storage = {
  getCartId: () => read(CART_ID),
  setCartId: (cartId: string | null) => write(CART_ID, cartId),
  getLastOrderId: () => read(LAST_ORDER_ID),
  setLastOrderId: (orderId: string) => write(LAST_ORDER_ID, orderId),
  getLastCouponCode: () => read(LAST_COUPON_CODE),
  setLastCouponCode: (code: string) => write(LAST_COUPON_CODE, code),
};
