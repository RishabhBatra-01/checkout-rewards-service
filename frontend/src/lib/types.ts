/**
 * Mirrors the backend response records exactly. All money is integer cents.
 */

export type CartStatus = "OPEN" | "CHECKED_OUT";
export type CouponStatus = "AVAILABLE" | "REDEEMED";

export interface Product {
  id: number;
  name: string;
  priceCents: number;
  inventory: number;
}

export interface CartItem {
  productId: number;
  name: string;
  unitPriceCents: number;
  quantity: number;
  lineTotalCents: number;
}

export interface Cart {
  id: string;
  status: CartStatus;
  createdAt: string;
  items: CartItem[];
  subtotalCents: number;
}

export interface OrderItem {
  productId: number;
  name: string;
  unitPriceCents: number;
  quantity: number;
  lineTotalCents: number;
}

export interface Order {
  id: string;
  cartId: string;
  placedAt: string;
  items: OrderItem[];
  grossTotalCents: number;
  discountTotalCents: number;
  netTotalCents: number;
  couponCode: string | null;
  couponDiscountPercent: number | null;
}

export interface Coupon {
  id: string;
  code: string;
  milestone: number;
  discountPercent: number;
  status: CouponStatus;
  createdAt: string;
}

export interface Report {
  successfulOrderCount: number;
  grossRevenueCents: number;
  totalDiscountsCents: number;
  netRevenueCents: number;
  purchasedQuantityByProduct: {
    productId: number;
    name: string;
    quantityPurchased: number;
    revenueCents: number;
  }[];
  coupons: { generated: number; available: number; redeemed: number };
}
