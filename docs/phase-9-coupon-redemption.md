# Phase 9 — Coupon redemption at checkout

Spending a coupon. Reporting is the only business requirement left after this.

## What was built

**Migration** `V7__coupon_redemption.sql` — `coupons.redeemed_at`, the widened status
check, the coupon snapshot on `orders`, and four new invariants.

| File | Change |
|---|---|
| `CheckoutRequest` | **new** — optional body `{"couponCode": "..."}` |
| `CouponNotFoundException` | **new** — unknown code, `404` |
| `CouponNotRedeemableException` | **new** — already redeemed, `409` |
| `Coupon` | `redeemedAt`, and `discountOn(gross)` — the rounding rule |
| `CouponStatus` | `REDEEMED` joins `AVAILABLE` |
| `CouponRepository` | `findByCode`, and the conditional `redeem` |
| `Order` | coupon snapshot columns and `applyCoupon` |
| `OrderResponse` | exposes `couponCode` and `couponDiscountPercent` |
| `CheckoutService` | resolves, redeems, and applies the discount |
| `CheckoutController` | accepts the optional body |
| `IdempotencyKeyReuseException` | a coupon mismatch is also an incompatible reuse |
| `ApiExceptionHandler` | two new codes |

### Contract

`POST /api/carts/{cartId}/checkout` — the body is optional; absent, null, or blank
means no coupon and checkout behaves exactly as it did in Phase 6.

| Situation | Status | `code` |
|---|---|---|
| No coupon | `201` | — |
| Valid `AVAILABLE` coupon | `201`, discount applied and coupon redeemed | — |
| Unknown code | `404` | `COUPON_NOT_FOUND` |
| Already redeemed, or lost a race | `409` | `COUPON_ALREADY_REDEEMED` |
| Same key, different coupon | `409` | `IDEMPOTENCY_KEY_REUSED` |

```json
{
  "grossTotalCents": 25998,
  "discountTotalCents": 2600,
  "netTotalCents": 23398,
  "couponCode": "SAVE10-DC6FFB6A6C",
  "couponDiscountPercent": 10
}
```

## The rounding rule

This is the first place in the system where rounding can occur, so the rule is
chosen explicitly rather than inherited from whatever the language happens to do.

**Discount = round-half-up(gross × percent ÷ 100), in integer cents, clamped to the
gross total.**

```java
long roundedHalfUp = (grossTotalCents * discountPercent + 50) / 100;
return Math.min(roundedHalfUp, grossTotalCents);
```

Adding half the divisor before an integer division is round-half-away-from-zero, and
gross totals are never negative, so this is round-half-up. No `float` or `double`
appears anywhere on the path: prices are `long` cents from the database, line totals
are integer multiplications, the gross is an integer sum, and the discount is the
integer expression above.

**Rounding happens exactly once**, on the whole-order discount. `net = gross −
discount` is then exact by construction. The alternative — discounting each line and
summing — rounds once per line and lets the total drift by a cent per line away from
"10% off the order", which is what the customer was promised.

Half-up rounds in the customer's favour by at most one cent. Truncating would favour
the merchant. Either is defensible; the point is that it is a decision, written down
and pinned by tests rather than emerging from an implementation detail.

The clamp makes a negative total structurally impossible. With the percentage
constrained to 1–100 it is already unreachable — at 100%, `(g*100+50)/100` is exactly
`g` — but the clamp costs nothing and defends against a future change to that range.
The database enforces `net_total_cents >= 0` independently.

Cases pinned in the tests:

| Gross | Percent | Discount | Net | Why |
|---|---|---|---|---|
| 1005 | 10 | 101 | 904 | 100.5 rounds up |
| 1004 | 10 | 100 | 904 | 100.4 rounds down |
| 1015 | 10 | 102 | 913 | 101.5 rounds up |
| 333 | 33 | 110 | 223 | 109.89 rounds to 110 |
| 12999 | 100 | 12999 | 0 | lands exactly on zero, never below |

## How redemption works, and why two checkouts cannot both do it

One conditional statement, the same shape as the inventory decrement from Phase 6A:

```sql
update coupons set status = 'REDEEMED', redeemed_at = :now
 where id = :couponId and status = 'AVAILABLE'
```

It returns 1 or 0, and 0 becomes `409 COUPON_ALREADY_REDEEMED`.

The coupon row *is* read first — for its code and percentage — but **that read is not
the decision**. It can be stale by the time the write happens; the write cannot be.
PostgreSQL takes a row lock for the duration of the `UPDATE` and, under
`READ COMMITTED`, a blocked update re-evaluates its `WHERE` clause against the newly
committed row. So of N concurrent checkouts holding the same code, exactly one sees a
row affected.

This is the third use of the same pattern — inventory in 6A, idempotency keys in 6B,
coupons here — and deliberately so. One mechanism, understood once, applied wherever
a check and an act must not come apart.

Measured with five threads released together: **one success, four
`CouponNotRedeemableException`, the coupon `REDEEMED`, and exactly one order carrying
its code.** The overlap gauge from Phase 7 asserts the threads genuinely ran at the
same time, so the test cannot pass on a sequential run.

## How a failed checkout rolls the coupon back

Everything is one transaction, so the rollback itself is free. The interesting part
is the ordering.

**The coupon is redeemed before stock is taken.** That is deliberate. If redemption
came last, a checkout that failed on inventory would never have touched the coupon,
and "a failed checkout does not consume the coupon" would be trivially true for the
wrong reason — untested and unproven. Redeeming first means a stock failure happens
*after* the coupon has been flipped to `REDEEMED` inside the transaction, so only the
rollback can put it back.

Verified live: after a `409 INSUFFICIENT_INVENTORY`, the coupon read
`AVAILABLE / redeemed_at=null`, and the test then successfully redeems it on a
subsequent checkout.

The trade-off, accepted: the coupon row stays locked for the rest of the checkout,
including every inventory update. For a handful of lines that is microseconds, and
coupons are single-use so contention on one is a brief burst rather than a sustained
load. If a slow external call ever enters the checkout transaction, this ordering
should be revisited.

## Why the order snapshots the coupon

`orders.coupon_code` and `orders.coupon_discount_percent` are **copies**, not a
reference to the coupon row.

Exactly the same reasoning as product names and prices in Phase 6A: the order has to
explain its own discount after the coupon is redeemed, edited, or deleted. A join to
`coupons` at read time would report what the coupon says today, not what the customer
was actually charged. Reporting sums these columns, so the numbers must be settled at
the moment of purchase and never move again.

## The database invariants added

Four new check constraints, because Phase 10 is going to sum these columns and a
report is only as trustworthy as the rows underneath it.

| Constraint | Prevents |
|---|---|
| `orders_totals_reconcile` | `net != gross - discount` |
| `orders_discount_requires_coupon` | a discount with no coupon to explain it |
| `orders_coupon_fields_together` | a code without a percentage, or vice versa |
| `coupons_redeemed_at_matches_status` | `REDEEMED` with no timestamp, or a timestamp while `AVAILABLE` |

All four were tested with direct `psql` inserts, outside the application. The first
of those is the one that matters most: it makes a row that does not reconcile
impossible to write, from any client, forever.

## Design choices

**An optional JSON body rather than a query parameter.** `{"couponCode": "..."}` with
`@RequestBody(required = false)`, so every existing bodyless checkout is untouched.
Coupon codes stay out of URLs, and therefore out of access logs and browser history.
The body also has somewhere to grow if checkout ever needs another input.

**A blank or null code means "no coupon", not a validation error.** `{"couponCode":
null}` and `{"couponCode": ""}` plainly mean the same thing as sending no body, and
treating them differently would be pedantry with no correctness benefit. The code is
trimmed before lookup, so trailing whitespace does not produce a spurious `404`.

**An unknown coupon fails before any stock is taken.** The requirements do not say
where this check belongs. Failing fast means a mistyped code costs the customer
nothing: the cart is still `OPEN`, no inventory moved, nothing to undo. Asserted in
the test rather than left to chance.

**Idempotency now compares the coupon.** Same key with a different coupon code is
`409 IDEMPOTENCY_KEY_REUSED`, consistent with the same-key-different-cart rule from
Phase 6B. This is slightly beyond the brief, but the coupon is now part of the
request, and leaving it out would have made the idempotency contract quietly wrong —
a client could retry with a different coupon and silently receive the original order.

**`discountOn` lives on `Coupon`.** The coupon owns the percentage, so it owns the
rule for turning that percentage into cents. It is also a pure function, which is why
the rounding table above can be tested directly.

## Verified

- `./mvnw clean test` — **54 tests pass**, 12 new: checkout without a coupon, a valid
  coupon applying the correct discount, an unknown coupon rejected, an
  already-redeemed coupon rejected, a failed checkout not consuming the coupon, five
  concurrent checkouts unable to double-redeem, gross/discount/net persisted, and
  five parameterised rounding cases.
- **Not flaky** — three consecutive clean runs of the coupon class.
- Live against Supabase: no coupon → `25998 / 0 / 25998`; a 10% coupon →
  `25998 / 2600 / 23398` with the code and percentage on the order; the same coupon
  again → `409 COUPON_ALREADY_REDEEMED`; an unknown code → `404 COUPON_NOT_FOUND`; a
  checkout that failed on stock → coupon still `AVAILABLE`, `redeemed_at` null.
- Totals read back from the database, not from the response that created them.
- All four new constraints rejected direct bad inserts.

### Mutation checks

| Mutation | What failed |
|---|---|
| `redeem` made unconditional — drop `and status = 'AVAILABLE'` | `anAlreadyRedeemedCouponIsRejected` → `expected:<409> but was:<201>`, and the concurrency test on `[exactly one checkout redeems the coupon]` |
| Truncate instead of rounding half up | Four assertions: `<2600>/<2599>`, `<101>/<100>`, `<102>/<101>`, `<110>/<109>` |

The second is worth keeping. A rounding rule with no test is a rule nobody has agreed
to; the mutation shows the tests would catch a silent change of policy, not just a
crash.

### A cleanup during verification

Testing the rollback live needed an `AVAILABLE` coupon, so one was inserted directly
into Supabase claiming milestone 2 — which had not been earned, since only seven
orders exist. Left in place it would have skewed the next generation: the service
would have computed milestone 3 and demanded fifteen orders. It was deleted once the
check was done. Supabase now reads seven orders, one coupon, highest milestone 1.

## Open items

- **Coupons have no owner and no expiry.** Any code can be redeemed by any cart, and
  a coupon is valid forever. That matches the assessment, which describes a coupon as
  simply "available", but it is a decision rather than an oversight — a real system
  would scope a reward to the customer who earned it.
- **A redeemed coupon can only be released by a rollback.** There is no
  administrative un-redeem, so a coupon consumed by an order that is later cancelled
  would stay spent. Cancellation does not exist yet, which is the only reason this is
  not a gap.
- **One coupon per checkout, no stacking.** The request carries a single code and the
  discount is a single percentage of the gross. Multiple coupons would raise ordering
  and compounding questions the requirements do not ask.
- **The product-lock ordering issue from Phase 7 is still open.** Coupon before
  products is a consistent global order, so redemption adds no new deadlock cycle,
  but products among themselves are still locked in `cart_items.id` order.
- **Row-level security is still disabled** on all six tables.

## Next phase

**Reporting** — the last business requirement:

- successfully purchased quantity by product;
- gross revenue before discounts, total discounts granted, net revenue;
- coupons generated, available, and redeemed;
- total successfully placed orders.

Every figure has a single source already, which is the payoff for the snapshotting
and the constraints: quantities from `order_items`, the three money figures from the
`orders` columns that `orders_totals_reconcile` guarantees agree, coupon counts from
`coupons.status`, and the order count from `count(*)`.

The requirement that repeated report requests must not mutate state is satisfied by
construction — it is a set of aggregate queries in a read-only transaction. The work
worth doing carefully is proving the report reconciles with the orders and coupons
the API itself returns, rather than merely that it returns numbers.
