import type { Cart, Coupon, Order, Product, Report } from "./types";

/**
 * A failed API call. `code` is the backend's stable machine-readable discriminator
 * (several distinct conditions legitimately share HTTP 409), and `detail` is the
 * backend's own human-readable message, which the UI shows rather than inventing
 * its own wording.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly detail: string,
    readonly title: string | null = null,
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

/** Not every failure is RFC 7807: a malformed UUID in a path yields Spring's default
 *  error body, which has no `code`. Both shapes are handled here so no component has
 *  to care which one it received. */
async function toApiError(response: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    return new ApiError(response.status, null, `${response.status} ${response.statusText}`);
  }

  const problem = body as Record<string, unknown>;
  const detail =
    (typeof problem.detail === "string" && problem.detail) ||
    (typeof problem.message === "string" && problem.message) ||
    (typeof problem.error === "string" && problem.error) ||
    `${response.status} ${response.statusText}`;

  return new ApiError(
    response.status,
    typeof problem.code === "string" ? problem.code : null,
    detail,
    typeof problem.title === "string" ? problem.title : null,
  );
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...init, cache: "no-store" });
  } catch {
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "Could not reach the server. Check that the backend is running on port 8080.",
    );
  }

  if (!response.ok) throw await toApiError(response);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

function jsonRequest<T>(path: string, method: string, body?: unknown, headers: HeadersInit = {}) {
  return request<T>(path, {
    method,
    headers: body === undefined ? headers : { "Content-Type": "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const api = {
  listProducts: () => request<Product[]>("/products"),

  createCart: () => jsonRequest<Cart>("/carts", "POST"),
  getCart: (cartId: string) => request<Cart>(`/carts/${cartId}`),
  addItem: (cartId: string, productId: number, quantity: number) =>
    jsonRequest<Cart>(`/carts/${cartId}/items`, "POST", { productId, quantity }),
  updateItem: (cartId: string, productId: number, quantity: number) =>
    jsonRequest<Cart>(`/carts/${cartId}/items/${productId}`, "PATCH", { quantity }),
  removeItem: (cartId: string, productId: number) =>
    jsonRequest<Cart>(`/carts/${cartId}/items/${productId}`, "DELETE"),

  /**
   * The idempotency key is supplied by the caller, not generated here, because it
   * must stay stable across retries of one checkout attempt.
   */
  checkout: (cartId: string, idempotencyKey: string, couponCode?: string | null) =>
    jsonRequest<Order>(
      `/carts/${cartId}/checkout`,
      "POST",
      couponCode ? { couponCode } : undefined,
      { "Idempotency-Key": idempotencyKey },
    ),

  getOrder: (orderId: string) => request<Order>(`/orders/${orderId}`),

  generateCoupon: () => jsonRequest<Coupon>("/admin/coupons/generate", "POST"),
  getReport: () => request<Report>("/admin/report"),
};
