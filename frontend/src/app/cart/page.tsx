"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { QuantityStepper } from "@/components/QuantityStepper";
import { ApiError, api } from "@/lib/api";
import { formatCents } from "@/lib/money";
import { storage } from "@/lib/storage";
import type { Cart } from "@/lib/types";

export default function CartPage() {
  const router = useRouter();
  const [cart, setCart] = useState<Cart | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [couponCode, setCouponCode] = useState("");
  const [error, setError] = useState<unknown>(null);

  /**
   * One idempotency key per checkout attempt, held across retries so a request that
   * timed out but actually succeeded replays the original order instead of creating
   * a second one. Editing the coupon makes it a materially different request, so the
   * key is reissued — otherwise the backend would (correctly) reject the retry as a
   * reused key.
   */
  const attempt = useRef<{ key: string; coupon: string } | null>(null);

  useEffect(() => {
    const cartId = storage.getCartId();
    // Resolved either way, so every state update lands in a microtask rather than
    // synchronously inside the effect body.
    const load = cartId ? api.getCart(cartId) : Promise.resolve(null);
    load
      .then(setCart)
      .catch((caught) => {
        storage.setCartId(null);
        if (!(caught instanceof ApiError && caught.status === 404)) setError(caught);
      })
      .finally(() => setLoading(false));
  }, []);

  // Every cart mutation returns the whole cart, so the response is the new state and
  // no refetch is needed.
  const mutate = useCallback(async (change: () => Promise<Cart>) => {
    setBusy(true);
    setError(null);
    try {
      setCart(await change());
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }, []);

  async function checkout() {
    if (!cart) return;
    const coupon = couponCode.trim();
    if (!attempt.current || attempt.current.coupon !== coupon) {
      attempt.current = { key: crypto.randomUUID(), coupon };
    }

    setBusy(true);
    setError(null);
    try {
      const order = await api.checkout(cart.id, attempt.current.key, coupon || null);
      attempt.current = null;
      storage.setLastOrderId(order.id);
      storage.setCartId(null); // the cart is CHECKED_OUT and cannot be reused
      router.push(`/orders/${order.id}`);
    } catch (caught) {
      setError(caught);
      setBusy(false);
    }
  }

  if (loading) return <p className="muted">Loading cart…</p>;

  if (!cart || cart.items.length === 0) {
    return (
      <>
        <h1>Your cart</h1>
        <ErrorBanner error={error} />
        <div className="card empty">
          <p>Your cart is empty.</p>
          <Link href="/">Browse products</Link>
        </div>
      </>
    );
  }

  const checkedOut = cart.status !== "OPEN";

  return (
    <>
      <h1>Your cart</h1>
      <p className="page-intro">Prices are confirmed at checkout, not when items are added.</p>

      <ErrorBanner error={error} />

      {checkedOut && (
        <div className="banner error">
          This cart has already been checked out. <Link href="/">Start a new one</Link>.
        </div>
      )}

      <div className="card">
        {cart.items.map((item) => (
          <div className="line" key={item.productId}>
            <div className="info">
              <div className="product-name">{item.name}</div>
              <div className="muted small">{formatCents(item.unitPriceCents)} each</div>
            </div>
            <QuantityStepper
              quantity={item.quantity}
              disabled={busy || checkedOut}
              onChange={(quantity) => mutate(() => api.updateItem(cart.id, item.productId, quantity))}
            />
            <div className="price" style={{ minWidth: 80, textAlign: "right" }}>
              {formatCents(item.lineTotalCents)}
            </div>
            <button
              type="button"
              className="link"
              disabled={busy || checkedOut}
              onClick={() => mutate(() => api.removeItem(cart.id, item.productId))}
            >
              Remove
            </button>
          </div>
        ))}

        <div className="totals">
          <div className="row grand">
            <span>Subtotal</span>
            <span className="price">{formatCents(cart.subtotalCents)}</span>
          </div>
        </div>
      </div>

      <h2>Coupon</h2>
      <div className="card">
        <label htmlFor="coupon" className="muted small">
          Optional. Earned coupons are issued from the admin page.
        </label>
        <div style={{ marginTop: 8 }}>
          <input
            id="coupon"
            type="text"
            placeholder="e.g. SAVE10-A1B2C3D4E5"
            value={couponCode}
            disabled={busy || checkedOut}
            onChange={(event) => setCouponCode(event.target.value)}
          />
        </div>
      </div>

      <div style={{ marginTop: 20 }}>
        <button type="button" className="primary block" disabled={busy || checkedOut} onClick={checkout}>
          {busy ? "Placing order…" : `Checkout · ${formatCents(cart.subtotalCents)}`}
        </button>
      </div>
    </>
  );
}
