# Phase 6A — Checkout and orders

Turning an open cart into an order, in one transaction. No idempotency keys, no
coupons, no concurrency tests yet.

## What was built

**Migration** `V4__create_orders.sql` — `orders` and `order_items`.

**New package `order`**

| File | Role |
|---|---|
| `Order` | aggregate root; owns its lines and keeps totals consistent with them |
| `OrderItem` | one purchased line, frozen at checkout |
| `OrderRepository` | lookup, plus a fetch-joined load for rendering |
| `OrderResponse` / `OrderItemResponse` | response records |
| `OrderController` | `GET /api/orders/{orderId}` |
| `OrderNotFoundException` | domain error |

**New package `checkout`** — `CheckoutService` (the transaction) and
`CheckoutController` (the endpoint).

**Touched** — `Cart.markCheckedOut()`, `ProductRepository.decrementInventory`,
three new exceptions (`CartNotOpenException`, `EmptyCartException`,
`InsufficientInventoryException`), and `ApiExceptionHandler`, which now attaches a
stable `code` to every problem response.

### Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/carts/{cartId}/checkout` | `201 Created`, `Location: /api/orders/{id}` | `404` unknown cart, `409` cart not open, `409` cart empty, `409` insufficient inventory |
| `GET` | `/api/orders/{orderId}` | `200 OK` | `404` unknown order, `400` malformed UUID |

```json
{
  "id": "2b7a12df-f0da-4a42-99b6-62618e41a548",
  "cartId": "09c39639-ff64-4371-8e81-d9924263872a",
  "placedAt": "2026-09-04T17:54:55.312792Z",
  "items": [
    { "productId": 1, "name": "Mechanical Keyboard", "unitPriceCents": 12999, "quantity": 2, "lineTotalCents": 25998 },
    { "productId": 2, "name": "Wireless Mouse",      "unitPriceCents":  4550, "quantity": 1, "lineTotalCents":  4550 }
  ],
  "grossTotalCents": 30548,
  "discountTotalCents": 0,
  "netTotalCents": 30548
}
```

Errors carry a machine-readable `code` next to the RFC 7807 fields:

```json
{ "type": "about:blank", "title": "Insufficient inventory", "status": 409,
  "detail": "Product 5 (Limited Edition Vinyl) has 3 in stock but 10 were requested",
  "instance": "/api/carts/afab564e-.../checkout", "code": "INSUFFICIENT_INVENTORY" }
```

## How the checkout transaction works

One `@Transactional` method. The order of the steps is part of the design, not an
accident.

1. **Lock the cart** — `CartRepository.findByIdForUpdate`, a `SELECT ... FOR UPDATE`.
   Every operation that mutates a cart takes this same lock, so nothing can
   interleave between the status check below and the write that follows it.
2. **Reject a cart that is not `OPEN`** → `409 CART_NOT_OPEN`.
3. **Reject an empty cart** → `409 CART_EMPTY`.
4. **For each line: take stock, then snapshot.** Prices are read here, at checkout,
   and copied onto the order line.
5. **Mark the cart `CHECKED_OUT`.**
6. **Save the order**, cascading its lines.

Reading the price *before* decrementing is deliberate. `decrementInventory` is a
`@Modifying` native query, which bypasses the persistence context; reading the
`Product` entity afterwards would risk reading a stale cached value. Capturing the
snapshot first removes the question entirely.

Everything above is one transaction, so any failure rolls back all of it — see
"What happens when checkout fails halfway" below.

## Why order items contain snapshots

The assessment requires that an order retains enough information to explain what
was purchased *"even if product data later changes"*. An order that joins to
`products` at read time cannot do that: it reports today's catalogue, not what the
customer was charged.

So each line stores its own copy:

```sql
product_id, product_name, unit_price_cents, quantity, line_total_cents
```

`product_id` is mapped as a plain `Long`, not a `@ManyToOne` association. That is
the whole point — the line must be readable and correct without consulting the
catalogue. The foreign key is still there for integrity, because products are never
hard-deleted in this system and a bogus id would be a bug worth catching, but no
read path depends on it.

This is the other half of the Phase 4 decision. A **cart item references** the
product, so an open cart always shows live prices and promises nothing. An **order
line copies** it, because the moment of purchase is when the price stops being a
quote and becomes a charge.

`line_total_cents` is stored even though `unit_price_cents * quantity` would
reproduce it. An order is a statement of what was charged; recomputing it later is
exactly how totals drift when a rounding rule changes. When per-line discounts
arrive, the stored value stays authoritative.

**Proved, not assumed.** After a checkout, the product was renamed to
"Renamed Keyboard" and repriced to 99999 directly in the database. The order still
reports `Mechanical Keyboard`, `12999`, gross `30548`, while `/api/products` reports
the new values. The two disagree, which is the correct outcome.

## How inventory is protected

The check and the decrement are the same statement:

```sql
update products set inventory = inventory - :quantity
 where id = :productId and inventory >= :quantity
```

It returns 1 if stock was taken and 0 if there was not enough, and 0 becomes
`409 INSUFFICIENT_INVENTORY`. There is **no separate read** that could go stale
between deciding and acting, and PostgreSQL holds a row lock for the duration of
the `UPDATE`, so concurrent callers are serialised per product.

The alternative — read the inventory, compare it in Java, then write the new value —
has a race in the gap between the read and the write, and no amount of application
code closes it without a lock. Pushing the condition into the `WHERE` clause makes
the gap non-existent rather than small.

Behind that sits `check (inventory >= 0)` from V1. Overselling would require both
the conditional guard and the check constraint to fail. A direct
`update products set inventory = -1` was rejected by the database.

### The same shape guards double checkout

Three layers, in increasing order of last resort:

1. The cart row lock serialises concurrent checkouts of one cart.
2. The `status != OPEN` check rejects the second one with a useful error.
3. `orders.cart_id unique` is the database backstop — a direct duplicate insert was
   rejected with `orders_cart_id_key`.

This refines what the Phase 3 doc predicted. That doc said the guard would be a
single conditional `update carts set status = 'CHECKED_OUT' where id = ? and
status = 'OPEN'`. Checkout needs the row lock anyway, to read the cart's items
consistently, and once the lock is held a plain status check is equally safe. The
conditional update would have been a second mechanism doing the same job, so the
lock plus the unique constraint won on having one fewer moving part.

## What happens when checkout fails halfway through

The transaction rolls back completely:

- stock already taken for earlier lines is returned;
- no order row and no order line survive;
- the cart stays `OPEN`, so the customer can fix it and try again.

The rollback is the only thing making this safe. Stock for the first line is taken
*before* the second line is known to fail, so without it inventory would leak on
every failed checkout.

Verified live with a cart holding product 1 (plenty of stock, its decrement
succeeds) and product 5 (3 in stock, 99 requested, fails):

```
before: {1: 48, 2: 119, 3: 25, 4: 40, 5: 3}
409 INSUFFICIENT_INVENTORY
after:  {1: 48, 2: 119, 3: 25, 4: 40, 5: 3}    <- product 1 was restored
cart still open: OPEN
orders for that cart: 0
```

Without the rollback, product 1 would read 43.

## Design choices

**No `status` column on `orders`.** An order row only exists if checkout succeeded,
so a status whose only value is `PLACED` would carry no information. "Total
successfully placed orders" is `count(*)`. Cancellation and refunds would introduce
a real lifecycle; nothing here does.

**`discount_total_cents` and `net_total_cents` exist now, always 0 and equal to
gross.** This goes slightly beyond the phase. The reporting requirement asks for
gross revenue, total discounts and net revenue to reconcile, and keeping all three
on one row means they cannot disagree with each other. Coupons will populate the
middle column and nothing else changes. The alternative — a single `total_cents`
now — needs a migration that backfills two columns later, for no benefit today.
This is a different case from the `updated_at` column deferred in Phase 3: all
three of these are written on every insert, so none is an unmaintained column.

**`Order` owns its lines with `cascade = ALL, orphanRemoval = true`; `Cart` does
not.** The difference is how they are written. An order and its lines are created
once, together, in a single transaction, and a line is meaningless without its
order — a textbook aggregate. Cart items are added, updated and removed
independently over the life of a cart, so managing them through a cascaded
collection would buy lazy-loading traps for no gain.

**Three different conditions answer `409`, distinguished by `code`.** `CART_EMPTY`,
`CART_NOT_OPEN` and `INSUFFICIENT_INVENTORY` are all "the current state of the
resource conflicts with this request", so they share a status. Inventing distinct
statuses to tell them apart would abuse the status code; a stable `code` field is
what a client should branch on. Every problem response now carries one, including
the earlier 404s and the validation 400.

**Checkout lives in its own package.** It orchestrates cart, product and order, so
putting it in any one of them would create a cycle. `checkout` depends on all three
and nothing depends on it.

**`GET /api/orders/{orderId}` uses a fetch join and no service.** `left join fetch
o.items` initialises the lines, so the response can be mapped outside a transaction
— which matters because `open-in-view` is disabled. That leaves nothing for a
service to do, so the controller talks to the repository directly, as
`ProductController` does.

**`201 Created` with `Location: /api/orders/{id}`.** Checkout genuinely creates a
new resource, unlike the cart mutations that return `200`. The body is the order
rather than the cart, because the order is what the client now cares about.

## Verified

- `./mvnw clean test` — **30 tests pass**, 9 of them new, covering: successful
  checkout, empty cart rejected, insufficient inventory rejected, inventory reduced
  by the purchased quantity, cart becomes `CHECKED_OUT`, second checkout rejected,
  order lines carry the name and price from checkout time, repricing and renaming a
  product afterwards does not rewrite the order, and a failed checkout leaves no
  order while returning stock taken for earlier lines.
- Live against Supabase:
  - checkout → `201` with `Location`, gross `30548` = `25998 + 4550`, inventory
    `50 → 48` and `120 → 119`, cart `CHECKED_OUT`
  - `GET /api/orders/{id}` → `200` with both snapshot lines
  - second checkout → `409 CART_NOT_OPEN`
  - empty cart → `409 CART_EMPTY`
  - 10 vinyl against 3 in stock → `409 INSUFFICIENT_INVENTORY`,
    `"has 3 in stock but 10 were requested"`
  - partial failure → full rollback, cart still `OPEN`, zero orders for that cart
- Inspected in the database rather than trusting the responses: `orders` and
  `order_items` rows, and a count of zero orders for the rolled-back cart.
- Database guards tested independently of the application: a duplicate
  `orders.cart_id` insert and a negative inventory update were both rejected.

### Two test bugs found and fixed

Both were faults in the tests, not the implementation, and both came from the same
cause: this test class shares one database across all its tests.

- The empty-cart test asserted a **global** `count(*) from orders`, which other
  tests in the class populate. It now counts orders for its own cart.
- `@BeforeEach` reset prices but not names, so the test that renames a product to
  "Renamed" leaked into the snapshot test that expects "Mechanical Keyboard".

The lesson recorded for later phases: any assertion about a global table is
order-dependent unless it is scoped to the row the test created.

## Open items

- **Retries are safe but unhelpful.** A client that times out and retries gets
  `409 CART_NOT_OPEN`. No second order is created, which is the important part, but
  the client cannot distinguish "my own retry already succeeded" from "this cart is
  no longer usable". Phase 6B fixes this.
- **No concurrency tests yet.** The mechanisms are in place — the conditional
  decrement, the cart lock, the unique constraint — but nothing yet proves them
  under real contention. That is the point of Phase 6B.
- **Payment is not modelled.** Successful checkout is treated as payment success,
  as the assessment permits. If a payment step were added it would belong between
  taking stock and committing, and would force the question of what to do when the
  payment provider times out — the same idempotency problem, one layer out.
- **Row-level security is still disabled** on all five tables. Supabase exposes the
  `public` schema through PostgREST, so `orders` and `order_items` are now reachable
  with the project's anon key outside this application. This mattered less when the
  data was a seeded catalogue; it matters more now.

## Next phase

**6B — retries and concurrency.**

**Idempotency first**, because the concurrency tests will assert its behaviour. The
open question from Phase 5 now has an easy answer: `orders.cart_id` is already
unique, so a checkout of an already-`CHECKED_OUT` cart can look up the order that
cart produced and return **`200` with the original order** instead of `409`. That
covers the timed-out-client retry with no new table, no new column and no
client-supplied header. A separate `Idempotency-Key` only earns its place for
requests that are not already keyed by a unique resource; checkout is.

Then the concurrency tests the assessment explicitly asks for:

- N threads checking out **different** carts that all contain the last units of
  "Limited Edition Vinyl" — assert stock never goes negative, and that the number of
  successful orders matches the stock consumed.
- N threads checking out the **same** cart — assert exactly one order exists, one
  caller gets `201`, and the rest get the same order back rather than an error.
