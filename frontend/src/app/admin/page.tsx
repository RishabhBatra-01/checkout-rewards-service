"use client";

import { useEffect, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { ApiError, api } from "@/lib/api";
import { formatCents } from "@/lib/money";
import { storage } from "@/lib/storage";
import type { Coupon, Report } from "@/lib/types";

export default function AdminPage() {
  const [report, setReport] = useState<Report | null>(null);
  const [coupon, setCoupon] = useState<Coupon | null>(null);
  const [lastCouponCode, setLastCouponCode] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reportError, setReportError] = useState<unknown>(null);
  const [couponError, setCouponError] = useState<unknown>(null);

  // State is only ever set from a promise callback, never synchronously in the
  // effect body.
  useEffect(() => {
    api.getReport().then(setReport).catch(setReportError);
    Promise.resolve(storage.getLastCouponCode()).then(setLastCouponCode);
  }, []);

  async function generate() {
    setBusy(true);
    setCouponError(null);
    try {
      const generated = await api.generateCoupon();
      setCoupon(generated);
      // There is no endpoint to list coupons, so a code that is not captured here
      // cannot be recovered through the API.
      storage.setLastCouponCode(generated.code);
      setLastCouponCode(generated.code);
      // Generating a coupon changes the coupon counts, so refresh the report.
      setReport(await api.getReport());
    } catch (caught) {
      setCouponError(caught);
    } finally {
      setBusy(false);
    }
  }

  const milestonePending =
    couponError instanceof ApiError && couponError.code === "MILESTONE_NOT_REACHED";

  return (
    <>
      <h1>Administration</h1>
      <p className="page-intro">Reward coupon generation and store reporting.</p>

      <h2>Reward coupons</h2>
      <div className="card">
        <div className="row-actions">
          <button type="button" className="primary" disabled={busy} onClick={generate}>
            {busy ? "Generating…" : "Generate coupon"}
          </button>
          <span className="muted small">
            Issues the coupon for the next milestone that has been earned.
          </span>
        </div>

        {coupon && (
          <div style={{ marginTop: 16 }}>
            <div className="banner ok" style={{ marginBottom: 8 }}>
              Coupon issued for milestone {coupon.milestone} — {coupon.discountPercent}% off. Copy
              it now; it cannot be retrieved again.
            </div>
            <div className="coupon-code">{coupon.code}</div>
          </div>
        )}

        {/* A milestone that is not yet due is an expected answer, not a failure. */}
        {milestonePending ? (
          <div className="banner" style={{ marginTop: 16, background: "#fff8e6", border: "1px solid #f0dca8" }}>
            {(couponError as ApiError).detail}
          </div>
        ) : (
          <div style={{ marginTop: 16 }}>
            <ErrorBanner error={couponError} />
          </div>
        )}

        {!coupon && lastCouponCode && (
          <p className="muted small" style={{ marginTop: 12 }}>
            Last coupon issued in this browser: <span className="code">{lastCouponCode}</span>
          </p>
        )}
      </div>

      <h2>Report</h2>
      <ErrorBanner error={reportError} />

      {!report && !reportError && <p className="muted">Loading report…</p>}

      {report && (
        <>
          <div className="stat-grid">
            <div className="stat">
              <div className="label">Orders</div>
              <div className="value">{report.successfulOrderCount}</div>
            </div>
            <div className="stat">
              <div className="label">Gross revenue</div>
              <div className="value">{formatCents(report.grossRevenueCents)}</div>
            </div>
            <div className="stat">
              <div className="label">Discounts</div>
              <div className="value">−{formatCents(report.totalDiscountsCents)}</div>
            </div>
            <div className="stat">
              <div className="label">Net revenue</div>
              <div className="value">{formatCents(report.netRevenueCents)}</div>
            </div>
          </div>

          <h2>Coupons</h2>
          <div className="stat-grid">
            <div className="stat">
              <div className="label">Generated</div>
              <div className="value">{report.coupons.generated}</div>
            </div>
            <div className="stat">
              <div className="label">Available</div>
              <div className="value">{report.coupons.available}</div>
            </div>
            <div className="stat">
              <div className="label">Redeemed</div>
              <div className="value">{report.coupons.redeemed}</div>
            </div>
          </div>

          <h2>Purchased by product</h2>
          <div className="card">
            {report.purchasedQuantityByProduct.length === 0 ? (
              <p className="muted" style={{ margin: 0 }}>Nothing sold yet.</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Product</th>
                    <th className="num">Quantity</th>
                    <th className="num">Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {report.purchasedQuantityByProduct.map((row) => (
                    <tr key={row.productId}>
                      <td>{row.name}</td>
                      <td className="num">{row.quantityPurchased}</td>
                      <td className="num">{formatCents(row.revenueCents)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <p className="muted small" style={{ marginTop: 14 }}>
            Figures come from order snapshots, so they do not change when product prices
            or names change.
          </p>
        </>
      )}
    </>
  );
}
