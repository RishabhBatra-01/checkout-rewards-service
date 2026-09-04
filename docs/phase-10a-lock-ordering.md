# Phase 10a — Deterministic product lock ordering

A follow-up fix, not a feature. It closes the last open item carried since Phase 7.

## What changed

One production file. `CheckoutService` split a single loop into two.

| File | Change |
|---|---|
| `checkout/CheckoutService` | stock is taken in ascending product id; order lines still built in cart order |
| `checkout/CheckoutLockOrderingTest` | **new** — concurrent checkouts of carts holding the same products in opposite order |

No migration, no API change, no change to checkout semantics, idempotency, coupons,
or the database.

## The defect

Checkout took stock by iterating `cartItems.findByCartId(cartId)`, which is ordered
by `cart_items.id` — that is, the order the customer happened to add things.

Two carts holding the same two products in opposite order therefore locked the
product rows in opposite sequences:

```
cart A:  lock product 1  ->  wants product 2
cart B:  lock product 2  ->  wants product 1
```

Each holds what the other is waiting for. PostgreSQL detects the cycle, aborts one
transaction with `deadlock detected`, and that surfaces to the caller as a failed
checkout — not a retryable error the client could act on, but a 500. A sale lost to
an implementation detail of the order someone clicked "add to cart".

This was recorded as theoretical in the Phase 7 document. It was not theoretical: it
reproduces readily under concurrent load, as the mutation check below shows.

## The fix

Take the locks in a single global order that every checkout agrees on. Any total
order works; ascending product id is stable, already unique, and needs nothing new.

```java
// Stock is taken in ascending product id rather than cart order. Two carts
// holding the same products in a different order would otherwise lock the
// product rows in opposite sequences, which is the classic deadlock: each holds
// what the other is waiting for. A single global ordering makes the cycle
// impossible rather than merely unlikely.
for (CartItem item : byAscendingProductId(items)) {
    takeStock(item.getProduct(), item.getQuantity());
}

// Lines keep the cart's own order, so what the customer sees is unaffected.
Order order = Order.forCart(cartId, key);
for (CartItem item : items) {
    ...
}
```

Plus a four-line private helper. That is the whole change.

The point worth keeping in mind: a consistent lock order does not make deadlocks
*rare*, it makes them **unreachable**. There is no interleaving of two transactions
acquiring locks in the same sequence that forms a cycle.

### Why two loops rather than sorting once

Sorting a single combined loop would have been shorter, but it would also have
reordered the order lines, changing the line order in every checkout response and in
`GET /api/orders/{id}`. Splitting the loop keeps two concerns separate: **locks are
acquired in a global order, lines are built in the customer's order.** Nothing
observable changes, which is what "do not change the semantics" asks for.

### Why not fix it in the repository query

`CartItemRepository.findByCartId` is also what renders the cart. Adding
`order by product_id` there would have silently reordered every cart response too.
The sort belongs where the locking happens, not in a query two callers share.

### The complete lock order now

Checkout acquires row locks in a fixed sequence: **cart → coupon → products by
ascending id.** Cart locks are never contended across different carts, so the only
shared resources are the coupon and the product rows, and both are now taken in the
same order by every transaction. No cycle is constructible.

## The test

`CheckoutLockOrderingTest` runs 10 rounds of 6 concurrent checkouts. Half the carts
list product A before B, the other half list B before A, and all six are released
together from a `CountDownLatch`. It asserts no checkout fails, all 60 complete, and
both products are decremented by exactly 60.

Several rounds rather than one pair, because a deadlock is probabilistic: two
transactions can interleave harmlessly by chance, and a single attempt would make a
weak detector.

## Verified

- **The mutation check is the evidence that matters.** Reverting
  `byAscendingProductId(items)` to `items` and running twice:

  ```
  mutated run 1: EXIT=1    ERROR: deadlock detected   (4 threads)
  mutated run 2: EXIT=1    ERROR: deadlock detected   (4 threads)
  ```

  PostgreSQL aborted transactions on both runs. The defect was real, not a
  hypothetical carried forward from an earlier document.

- **With the fix:** three consecutive runs of the lock-ordering test, `deadlock
  detected` appearing **0** times in any of them.

- **Full suite: 63 tests, 0 failures**, and `grep -c 'deadlock detected'` across the
  entire run returns **0**.

## Open items

Unchanged by this fix, and still outstanding:

- **Row-level security is disabled** on all six tables. Supabase exposes the `public`
  schema through PostgREST, so `orders`, `order_items` and `coupons` are reachable
  with the project's anon key. The most serious item on the list.
- **The report is not transaction-consistent across its three queries** (Phase 10).
- **No retry policy for transient serialization failures generally.** This fix removes
  the one deadlock the design could produce, but a production service would still want
  a bounded retry around transactions that fail with a transient SQL state, rather than
  relying on having removed every cause by construction.
- **`DECISIONS.md`, a README refresh, and repository history** remain the outstanding
  submission work.
