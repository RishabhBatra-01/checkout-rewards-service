"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { api } from "@/lib/api";
import { formatCents } from "@/lib/money";
import { storage } from "@/lib/storage";
import type { Cart, Product } from "@/lib/types";

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [cart, setCart] = useState<Cart | null>(null);
  const [busyProductId, setBusyProductId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    api.listProducts().then(setProducts).catch(setError);

    // Show the current cart size if this browser already has one. A cart that has
    // been checked out is no longer usable, so it is forgotten here rather than
    // failing later at checkout.
    const cartId = storage.getCartId();
    if (!cartId) return;
    api
      .getCart(cartId)
      .then((existing) => {
        if (existing.status === "OPEN") setCart(existing);
        else storage.setCartId(null);
      })
      .catch(() => storage.setCartId(null));
  }, []);

  async function addToCart(product: Product) {
    setBusyProductId(product.id);
    setError(null);
    try {
      let cartId = cart?.id ?? storage.getCartId();
      if (!cartId) {
        const created = await api.createCart();
        storage.setCartId(created.id);
        cartId = created.id;
      }
      setCart(await api.addItem(cartId, product.id, 1));
    } catch (caught) {
      setError(caught);
    } finally {
      setBusyProductId(null);
    }
  }

  const itemCount = cart?.items.reduce((total, item) => total + item.quantity, 0) ?? 0;

  return (
    <>
      <h1>Products</h1>
      <p className="page-intro">
        Adding to a cart does not reserve stock — availability is confirmed at checkout.
      </p>

      <ErrorBanner error={error} />

      {itemCount > 0 && (
        <div className="banner ok">
          {itemCount} {itemCount === 1 ? "item" : "items"} in your cart ·{" "}
          <Link href="/cart">View cart</Link>
        </div>
      )}

      {products === null && !error && <p className="muted">Loading products…</p>}

      {products && (
        <div className="grid">
          {products.map((product) => (
            <div className="card" key={product.id}>
              <p className="product-name">{product.name}</p>
              <p className="price">{formatCents(product.priceCents)}</p>
              <p className={`stock ${product.inventory === 0 ? "out" : product.inventory <= 5 ? "low" : "muted"}`}>
                {product.inventory === 0
                  ? "Out of stock"
                  : product.inventory <= 5
                    ? `Only ${product.inventory} left`
                    : `${product.inventory} in stock`}
              </p>
              <button
                type="button"
                className="primary block"
                disabled={product.inventory === 0 || busyProductId !== null}
                onClick={() => addToCart(product)}
              >
                {busyProductId === product.id ? "Adding…" : "Add to cart"}
              </button>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
