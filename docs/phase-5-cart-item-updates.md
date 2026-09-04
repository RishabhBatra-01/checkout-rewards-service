# Phase 5 — Updating and removing cart items

Finishing the cart item operations. No checkout, no inventory movement.

## What was built

No migration: `cart_items` from Phase 4 already had every column these operations
need. Two new files, four touched.

| File | Change |
|---|---|
| `UpdateCartItemRequest` | **new** — `{"quantity": n}`, validated `@NotNull @Positive` |
| `CartItemNotFoundException` | **new** — the cart exists but does not hold that product |
| `CartItem` | added `changeQuantityTo`, alongside the existing `increaseQuantityBy` |
| `CartService` | `updateItemQuantity` and `removeItem` |
| `CartController` | `PATCH` and `DELETE` on `/{cartId}/items/{productId}` |
| `ApiExceptionHandler` | `CartItemNotFoundException` → 404, title `Cart item not found` |

### Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| `PATCH` | `/api/carts/{cartId}/items/{productId}` | `200 OK`, updated cart | `400` non-positive or missing quantity, `404` unknown cart, `404` product not in cart |
| `DELETE` | `/api/carts/{cartId}/items/{productId}` | `200 OK`, updated cart | `404` unknown cart, `404` product not in cart |

Both return the whole cart, with totals already recalculated:

```json
{
  "id": "00279cec-8bf6-4ebb-b495-4b0696818a4a",
  "status": "OPEN",
  "items": [
    { "productId": 1, "name": "Mechanical Keyboard", "unitPriceCents": 12999, "quantity": 5, "lineTotalCents": 64995 }
  ],
  "subtotalCents": 64995
}
```

## How update differs from add

Add is **additive and creating**. Update is **replacing and never creating**.

| | `POST /items` | `PATCH /items/{productId}` |
|---|---|---|
| Product already in the cart | quantity **+=** n | quantity **=** n |
| Product not in the cart | inserts a row | `404` |
| Product id does not exist at all | `404 Product not found` | `404 Cart item not found` |
| Reads the catalogue | yes | no |

Verified live: an item at quantity 2, patched to 5, became **5 — not 7**.

That third row is a deliberate choice rather than an oversight. `addItem` has to
consult the catalogue, because it may be about to create a row referencing a
product, so it can distinguish "no such product" from "not in your cart". Update
and remove never touch `ProductRepository` at all: they look only inside the cart.

From the client's point of view "product 999 does not exist" and "product 3 exists
but is not in your cart" are the same answer to `PATCH .../items/{id}` — there is
nothing here to change. Collapsing them into one lookup and one exception means one
query instead of two, and no branch that can disagree with itself.

### Both operations reuse the cart's row lock

`updateItemQuantity` and `removeItem` load the cart with
`CartRepository.findByIdForUpdate` — the same `SELECT ... FOR UPDATE` that
`addItem` takes. Every mutation of a cart therefore serialises on the cart row, so
a concurrent add and remove cannot interleave into a lost update, and a concurrent
update and remove resolve in a defined order rather than racing.

This costs nothing extra: the lock was already there, and the pattern now covers
every write path into a cart.

## What happens when an item is removed

The row is deleted and the cart is returned with recalculated totals. Confirmed
against the database rather than only the response: after removing product 5,
`cart_items` for that cart held exactly one row, and the cart was still `OPEN`.

Three things deliberately do **not** happen.

**Inventory is untouched.** Nothing was ever held when the item was added, so there
is nothing to release. This is the same reasoning as Phase 4: a cart item is an
intention to buy, and the only place inventory moves is inside the checkout
transaction.

**Removal is not idempotent, by choice.** A second `DELETE` of the same item
returns `404`, not `200`. `DELETE` is conventionally idempotent in the sense that
repeating it leaves the resource in the same *state*, which holds here — the item
is absent either way. What differs is the report: the API says truthfully whether
there was anything to delete. A blind `204` would hide a client bug where the wrong
product id is being sent. If a caller genuinely wants "make sure this is gone"
semantics, that is a one-line change and worth revisiting when a real client asks.

**Emptying a cart does not delete or close it.** A cart with zero items is still a
valid `OPEN` cart with a stable id, and can be refilled. Deleting it would break the
id the client is holding; closing it would confuse an empty cart with a checked-out
one, which is the distinction the `status` column exists to protect.

## Design choices

**Both endpoints return `200` with the whole cart.** `204 No Content` is the more
conventional `DELETE` response, but every other cart mutation already returns the
updated cart, and totals change on every one of these operations. Returning the
cart saves the client a follow-up `GET` and keeps one response shape across the
whole cart API. The convention is real but weaker than the consistency here.

**`PATCH`, not `PUT`.** With a single mutable field the two are nearly
indistinguishable, but the request carries a partial representation of the item —
it says nothing about the product — so `PATCH` describes it more honestly. `PUT`
would imply the body is the complete item.

**A separate `CartItemNotFoundException` rather than reusing
`ProductNotFoundException`.** Both answer `404`, but they mean different things and
carry different titles, so a client that wants to react differently can. Reusing
the product exception would have made the message wrong: the product may be
perfectly valid.

**`changeQuantityTo` sits next to `increaseQuantityBy` on the entity.** Two small
named methods rather than one setter, because the distinction between them is the
entire behavioural difference between add and update. A bare `setQuantity` would
let a future caller pick the wrong semantics silently.

**No new abstraction.** No `CartItemService`, no mapper layer, no generic
"not found" base exception. The two methods sit in `CartService` next to `addItem`,
which is where anyone looking for cart behaviour will look.

**Validation rejects before any write.** `@Positive` on the request record means a
non-positive quantity never reaches the service, so the existing quantity is
untouched — asserted explicitly in the tests, not assumed.

## Verified

- `./mvnw clean test` — **21 tests pass**. Nine are new: successful update, invalid
  quantity (`0` and `-1`, each also asserting the stored quantity was left alone),
  update of a product not in the cart, update against an unknown cart, successful
  removal, removal of a product not in the cart, removal from an unknown cart, and
  inventory unchanged across an add → update → remove sequence.
- Live against Supabase:
  - `PATCH` product 1 from 2 to 5 → `quantity: 5`, `subtotalCents: 76991`
  - `PATCH {"quantity": 0}` → `400 {"title":"Invalid request","detail":"quantity must be greater than zero"}`
  - `PATCH` product 3, never added → `404 {"title":"Cart item not found","detail":"Cart 00279cec-... does not contain product 3"}`
  - `DELETE` product 5 → `200`, item gone, `subtotalCents` back to `64995`
  - `DELETE` the same item again → `404 Cart item not found`
  - Unknown cart → `404 Cart not found` on both verbs
  - Cart remained `OPEN` throughout
- Confirmed in the database, not only in the response: one row left in `cart_items`
  for the cart, cart status still `OPEN`.
- Inventory unchanged — Mechanical Keyboard 50, Limited Edition Vinyl 3.

## Open items

- Row-level security is still disabled on `products`, `carts` and `cart_items`.
  Supabase exposes the `public` schema through PostgREST, so these tables are
  reachable with the project's anon key outside this application. This becomes more
  serious once orders exist.
- Nothing prevents a cart from holding more units than are in stock. That is
  intentional — availability is a checkout concern — but it means the first real
  availability error a customer sees will be at checkout.
- Adding to, updating, or removing from a `CHECKED_OUT` cart is still not rejected,
  because nothing can produce that state yet. It lands with checkout.

## Next phase

Checkout, which is where the graded invariants actually live. Worth splitting:

**6a — the happy path with real snapshots.** `orders` and `order_items`, where each
line freezes `product_id`, `name`, `unit_price_cents` and `quantity`, so an order
explains itself after the catalogue changes. The single conditional
`update carts set status = 'CHECKED_OUT' where id = ? and status = 'OPEN'` that
makes a second checkout impossible. Inventory decremented with a conditional
`update products set inventory = inventory - ? where id = ? and inventory >= ?`, so
overselling fails at the database rather than depending on a prior read. Empty
carts rejected.

**6b — retries and concurrency.** An idempotency strategy so a client that times out
and retries receives the *same* order rather than creating a second one, plus the
concurrent tests the assessment asks for: N threads racing for the last unit of
"Limited Edition Vinyl", asserting exactly one order and no oversell.

One decision to settle before 6a: whether the idempotency key is a client-supplied
`Idempotency-Key` header or is derived from the cart id. The cart id is simpler and
nearly sufficient, since a cart can only be checked out once — the gap is that a
retry arriving after the cart is already `CHECKED_OUT` needs to return the original
order rather than an error.
