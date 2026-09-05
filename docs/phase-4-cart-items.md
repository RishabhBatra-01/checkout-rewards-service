# Phase 4 — Cart items

Adding a product to a cart, and showing a cart's contents. No checkout, no
inventory movement.

## What was built

**Migration** `V3__create_cart_items.sql` — a `cart_items` table.

**Domain** (`com.uniblox.store.cart`)

| File | Role |
|---|---|
| `CartItem` | JPA entity; one row per distinct product in a cart |
| `CartItemRepository` | lookup by cart, and by cart + product |
| `AddCartItemRequest` | request record, bean-validated |
| `CartItemResponse` | one priced line of a cart |
| `CartService` | **new** — the transaction boundary and the add-or-increment rule |

`CartController` now delegates to `CartService` instead of using repositories
directly, and `CartResponse` carries `items` and `subtotalCents`.

**Errors** — `ProductNotFoundException` (in the product package) and a validation
handler in `ApiExceptionHandler` that names the rejected field.

### Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/carts/{cartId}/items` | `200 OK`, the updated cart | `400` non-positive or missing quantity, `404` unknown cart, `404` unknown product |
| `GET` | `/api/carts/{cartId}` | `200 OK`, now including items and subtotal | `404`, `400` as before |

```json
{
  "id": "0803c878-7d67-4504-b989-424a7783a6fc",
  "status": "OPEN",
  "createdAt": "2026-09-04T17:23:50.044873Z",
  "items": [
    { "productId": 1, "name": "Mechanical Keyboard",   "unitPriceCents": 12999, "quantity": 5, "lineTotalCents": 64995 },
    { "productId": 5, "name": "Limited Edition Vinyl", "unitPriceCents":  2999, "quantity": 1, "lineTotalCents":  2999 }
  ],
  "subtotalCents": 67994
}
```

## How a cart item is stored

```sql
create table cart_items (
    id         bigserial primary key,
    cart_id    uuid    not null references carts (id) on delete cascade,
    product_id bigint  not null references products (id),
    quantity   integer not null check (quantity > 0),
    constraint cart_items_one_row_per_product unique (cart_id, product_id)
);
```

**A surrogate key with a unique constraint, not a composite primary key.** The
natural key really is `(cart_id, product_id)`, and making it the primary key would
express that directly — but JPA then needs `@IdClass` or `@EmbeddedId`. The unique
constraint enforces exactly the same invariant with an ordinary `Long` id, so the
entity stays plain.

**The item references the product; it does not copy it.** There is no name or price
column here. While a cart is open it reflects the live catalogue, so a price change
is visible the next time the cart is viewed. This is the answer to the assessment's
question about prices changing between adding an item and checking out: **the cart
never promises a price**. The customer pays what the catalogue says at the moment
the order is placed, and the order lines — added in a later phase — are what freeze
those values permanently.

The alternative is to snapshot the price into `cart_items` on add. That gives the
customer a price guarantee, but then a cart is a pricing contract with no expiry,
and stale carts quietly sell at last month's prices. Reading live and freezing at
checkout keeps the guarantee where it belongs: on the order.

**`on delete cascade` on `cart_id`, plain reference on `product_id`.** Deleting a
cart should take its items with it; deleting a product that sits in someone's cart
should be refused.

**Quantity is `integer not null check (quantity > 0)`.** A zero-quantity row is not
"an item with no quantity", it is a row that should not exist.

## What happens when the same product is added twice

The quantity on the existing row is increased. Adding 2 then 3 leaves **one** row
with quantity 5, not two rows.

Two independent layers enforce this:

1. **`CartService.addItem`** looks for an existing `(cart, product)` row and calls
   `increaseQuantityBy` on it, inserting only when there is none.
2. **The unique constraint** rejects a duplicate even if the service were wrong, or
   if something other than this application wrote to the table.

Layer 2 is not decoration — it was verified by inserting a duplicate directly in
`psql`, which the database refused.

### Why the operation takes a row lock

A read-modify-write has an obvious race: two concurrent requests both look for an
existing row, both find none, and both insert. One then fails on the unique
constraint, turning a legitimate request into a 500.

`addItem` therefore loads the cart with `SELECT ... FOR UPDATE`
(`CartRepository.findByIdForUpdate`, via `@Lock(PESSIMISTIC_WRITE)`). Every
mutation of a cart takes the same lock, so all writes to a single cart serialise:
the second request waits, then sees the row the first one inserted and increments
it. Contention is irrelevant in practice, because the lock is per cart and one
customer's cart is not a hot row.

This is four lines and it removes a real failure mode, so it went in now rather
than waiting for the concurrency phase. It is also exactly the mechanism checkout
will use, which makes it worth establishing early.

## Why inventory is not changed at this stage

A cart item is an **intention** to buy, not a claim on stock.

Decrementing inventory when an item is added would mean every abandoned cart
permanently destroys stock unless expiry and release are also built — a background
reaper, TTLs on carts, and compensation when the reaper and a checkout race. That
is a large amount of machinery.

The more important reason is that it would not even be correct. The check that
matters is the one at the moment of purchase. Stock reserved ten minutes ago says
nothing about whether it is still available now, so checkout has to verify
availability regardless. Reserving early adds machinery without removing the check
it was supposed to replace.

So carts are free to contain anything, including more units than exist, and there
is exactly one place where inventory is read and decremented: inside the checkout
transaction, where the check and the decrement can be made atomic. A cart that
cannot be fulfilled fails at checkout with a clear error — which is also where the
customer can actually be told about it.

## Design choices

**`POST /items` returns `200` with the whole cart, not `201` with the item.** The
request is not reliably a creation — sending the same product again updates an
existing row — so `201 Created` would be a lie half the time. Returning the updated
cart also saves the client a follow-up `GET` to refresh totals.

**Prices and totals were added to the cart response.** The instruction for this
phase said "includes the cart's current items", but the assessment requires
"useful prices and totals when viewing a cart", and the data comes from the same
query. Each line carries `unitPriceCents` and `lineTotalCents`, and the cart
carries `subtotalCents`.

**Money stays in integer cents.** `lineTotalCents = priceCents * quantity` is
integer multiplication and `subtotalCents` is an integer sum, so no rounding can
occur anywhere in this phase. Rounding only becomes a question when percentage
discounts arrive.

**A `CartService` now exists.** Phase 3 deliberately had none — creating a cart was
one constructor and one save. This phase introduced a real transaction boundary and
a real rule, which is what a service is for. Cart rendering lives there too, so the
lazy `product` association is always resolved inside a transaction, which matters
because `open-in-view` is disabled.

**Items are fetched with a join fetch.** `findByCartId` uses
`join fetch i.product`, so rendering a cart is one query regardless of how many
lines it has, rather than one query per line.

**No `CartItem` collection on `Cart`.** A bidirectional `@OneToMany` with cascade
and orphan removal would make `Cart` a full aggregate root, but it brings lazy
loading traps and cascade semantics that nothing needs yet. The service queries
items explicitly instead.

**Adding to a `CHECKED_OUT` cart is not rejected yet.** Nothing can produce that
state until checkout exists, so the guard would be untestable here. It was added once
checkout existed: `CartService` now loads a cart for modification through a single
guard that rejects any status other than `OPEN` with `409 CART_NOT_OPEN`.

## Verified

- `./mvnw clean test` — **12 tests pass**. Eight are new: add a product, retrieve a
  cart including items, repeat add increments a single row, quantity `0` and `-1`
  rejected, unknown cart, unknown product, and inventory unchanged after an add.
- Live against Supabase: adding `2` then `3` of product 1 returned one line with
  `quantity: 5` and `subtotalCents: 64995`; the cart stayed `OPEN`.
- Error contracts: `quantity: 0` →
  `400 {"title":"Invalid request","detail":"quantity must be greater than zero"}`;
  unknown product → `404 {"title":"Product not found"}`; unknown cart → `404`.
- Inventory untouched — Mechanical Keyboard stayed at 50, Limited Edition Vinyl at
  3, after items were added to carts.
- Constraints enforced by the database, not only the application. Direct `psql`
  inserts were rejected by `cart_items_one_row_per_product` (duplicate) and
  `cart_items_quantity_check` (`quantity = 0`).

### A verification failure worth recording

The first live run was invalid and briefly looked like the endpoint did not exist.
`./mvnw spring-boot:run` forks a **child** JVM; killing the Maven process leaves
that child running and holding port 8080. An orphan from an earlier phase was still
listening, so the curls were answered by pre-Phase-4 code and every item request
returned `404`.

The fix is to kill the application by its main class, not the build tool:

```bash
pkill -f "com.uniblox.store.StoreApplication"
```

Confirming which build is live is worth the extra second — checking for
`hibernate-validator` on the running process's classpath was what settled it, since
that jar only arrived with this phase.

## Open items

- `public.cart_items` has row-level security disabled, like `products` and `carts`.
  Supabase exposes the `public` schema through PostgREST, so these tables are
  reachable with the project's anon key outside this application.
- Updating and removing cart items are not implemented; only adding is.
