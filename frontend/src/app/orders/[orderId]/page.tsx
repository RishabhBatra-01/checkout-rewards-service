"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { api } from "@/lib/api";
import { formatCents } from "@/lib/money";
import type { Order } from "@/lib/types";

export default function OrderPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!orderId) return;
    api.getOrder(orderId).then(setOrder).catch(setError);
  }, [orderId]);

  if (error) {
    return (
      <>
        <h1>Order</h1>
        <ErrorBanner error={error} />
        <Link href="/">Back to products</Link>
      </>
    );
  }

  if (!order) return <p className="muted">Loading order…</p>;

  return (
    <>
      <h1>Order confirmed</h1>
      <p className="page-intro">
        Placed {new Date(order.placedAt).toLocaleString()} · Order{" "}
        <span className="code">{order.id}</span>
      </p>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">Unit price</th>
              <th className="num">Qty</th>
              <th className="num">Line total</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.productId}>
                <td>{item.name}</td>
                <td className="num">{formatCents(item.unitPriceCents)}</td>
                <td className="num">{item.quantity}</td>
                <td className="num">{formatCents(item.lineTotalCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="totals">
          <div className="row">
            <span className="muted">Gross</span>
            <span className="price">{formatCents(order.grossTotalCents)}</span>
          </div>
          {order.couponCode && (
            <div className="row">
              <span className="muted">
                Discount · {order.couponCode} ({order.couponDiscountPercent}%)
              </span>
              <span className="price">−{formatCents(order.discountTotalCents)}</span>
            </div>
          )}
          <div className="row grand">
            <span>Total paid</span>
            <span className="price">{formatCents(order.netTotalCents)}</span>
          </div>
        </div>
      </div>

      {/* Names and prices here are copies taken at checkout, so this page keeps
          reporting what was charged even after the catalogue changes. */}
      <p className="muted small" style={{ marginTop: 14 }}>
        Item names and prices are recorded as they were at the time of purchase.
      </p>

      <p style={{ marginTop: 20 }}>
        <Link href="/">Continue shopping</Link>
      </p>
    </>
  );
}
