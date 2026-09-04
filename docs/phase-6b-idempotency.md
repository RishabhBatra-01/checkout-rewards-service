# Phase 6B — Idempotent checkout

Making checkout safe to retry. The checkout flow from 6A is unchanged; this phase
adds a key, a constraint, and a replay path.

## What was built

**Migration** `V5__add_checkout_idempotency.sql` — adds `idempotency_key` to
`orders`, backfills the rows that existed before it, then makes it `not null`,
unique, and non-blank.

| File | Change |
|---|---|
| `CheckoutResult` | **new** — `(order, replayed)`, so the controller can answer 201 or 200 |
| `MissingIdempotencyKeyException` | **new** — missing or blank header |
| `IdempotencyKeyReuseException` | **new** — key already used for a different request |
| `Order` | carries `idempotencyKey`; `forCart` now takes it |
| `OrderRepository` | `findByIdempotencyKey`, fetch-joined |
| `CheckoutService` | key validation, replay lookup, conflict detection |
| `CheckoutController` | reads the header; 201 on create, 200 + `Idempotent-Replay` on replay |
| `ApiExceptionHandler` | two new codes |

### Contract

`POST /api/carts/{cartId}/checkout`, header `Idempotency-Key: <non-blank, ≤200 chars>`

| Situation | Status | `code` |
|---|---|---|
| First request with this key | `201 Created` + `Location` | — |
| Same cart, same key | `200 OK` + `Idempotent-Replay: true` | — |
| Same cart, **different** key, already checked out | `409` | `CART_NOT_OPEN` |
| **Different** cart, same key | `409` | `IDEMPOTENCY_KEY_REUSED` |
| Header missing or blank | `400` | `IDEMPOTENCY_KEY_REQUIRED` |

## What idempotency means here

**Retrying costs nothing.** A client that sends checkout, times out before seeing
the response, and sends it again gets the same order back — not a second order, not
a second inventory charge, and not an error it has to interpret.

The distinction the design rests on is between a **retry** and a **new request**:

- Same cart, same key — the same request arriving twice. Replay it.
- Same cart, different key — the client deliberately asked again. There is nothing
  to replay, and the cart is spent, so `CART_NOT_OPEN`.
- Different cart, same key — a different request wearing the same name. Replaying
  the first cart's order would answer a question this client never asked, so it is a
  conflict.

That last case is the "incompatible request" rule. Since checkout has no request
body, the cart id *is* the request, so "incompatible" reduces to "a different cart".

**Only successful checkouts are idempotent.** A checkout that fails — empty cart,
insufficient inventory — records no key, because the transaction rolls back. Retrying
with the same key genuinely retries, which is what a client wants: a failure is not a
result worth replaying, and the stock that was missing a moment ago may be back.

## How the database prevents duplicate processing

**No new table.** The key is a column on `orders`.

An order already *is* the record that a checkout happened: a cart produces at most
one, and that order is precisely what a retry must be handed back. A separate
idempotency ledger would have to be kept in step with `orders` and could disagree
with it — two sources of truth for one fact. Putting the key on the row it describes
makes disagreement impossible.

Two database mechanisms carry the guarantee, and neither lives in application memory:

1. **The cart's row lock.** `CartRepository.findByIdForUpdate` was already there from
   Phase 4. Checkout takes it before reading anything, so concurrent attempts on one
   cart are serialised rather than racing.
2. **`unique (idempotency_key)`.** Two orders cannot carry the same key, whichever
   instance inserts them, and regardless of whether the application logic is correct.

Alongside those, `check (length(btrim(idempotency_key)) between 1 and 200)`. Without
it a blank key would be a legal value, and every "unkeyed" retry from every client
would collide with the first one.

Both were tested directly in `psql`, outside the application: a duplicate key was
rejected by `orders_idempotency_key_unique`, a blank one by
`orders_idempotency_key_not_blank`.

### The migration had to handle existing data

`orders` already contained a row from Phase 6A, so `add column ... not null` would
have failed. The migration adds the column nullable, backfills
`'legacy-' || id::text` — unique by construction, and obvious about where it came
from — then applies `not null` and the constraints. Verified against Supabase: the
pre-existing order now reads `legacy-2b7a12df-...`.

## What happens when two identical requests arrive simultaneously

Both attempt to lock the cart row. PostgreSQL grants one and **blocks** the other.

The winner runs the normal checkout and commits, releasing the lock. The loser then
acquires it and re-reads — and this is the step that makes the whole thing work: its
lookup by idempotency key now sees the committed order, so it takes the replay path
and returns that order.

**This depends on the isolation level, and it is worth being explicit about.** Under
`READ COMMITTED` — PostgreSQL's default, and what this application runs at — each
statement takes a fresh snapshot, and a `SELECT ... FOR UPDATE` that waits for a lock
re-evaluates the row once granted. Under `REPEATABLE READ` the loser would still be
reading a snapshot from before the winner committed, would not see the order, and
would fail differently. The design is correct at the isolation level it actually
runs at, not at an assumed one.

If two requests share a key but target **different** carts, they lock different rows
and can both reach the insert. There the unique constraint decides: one commits, the
other's `saveAndFlush` raises a constraint violation that is translated into
`409 IDEMPOTENCY_KEY_REUSED`.

Measured, not assumed: 6 threads released together from a `CountDownLatch` produced
**one order, one inventory decrement, one creator, five replays, all returning the
same order id, and zero errors.**

## Why an in-memory lock would be insufficient

A `synchronized` block, a `ReentrantLock`, or a `ConcurrentHashMap` of seen keys
guards **one JVM**. Run two instances behind a load balancer and two retries land on
different pods, which share no memory. Both pass their local guard, both proceed.

Three specific ways it fails:

- **Horizontal scaling.** The guarantee evaporates the moment a second instance
  starts — exactly when traffic justifies one.
- **Restarts and deploys.** The map is lost, so a retry arriving after a rolling
  deploy is treated as new.
- **It tests green.** On a developer machine with one instance it appears to work
  perfectly, so the defect ships and only appears in production under load.

The database is the only thing every instance shares, so the invariant has to live
there. This is also why the concurrency test asserts `count(*)` in PostgreSQL rather
than inspecting objects on the heap: an assertion against the heap would pass for an
implementation that is wrong in exactly the way that matters.

## Design choices

**The key lives on `orders`, not in a dedicated table.** Covered above: one fact,
one row, no second source of truth to drift. The trade-off is that only *successful*
checkouts are recorded, so a failed attempt cannot be replayed — which is the
behaviour we want anyway.

**The header is declared `required = false`.** Letting Spring enforce it would make a
missing header a `MissingRequestHeaderException` and a blank one a service-level
error — two different failures for what is, to a client, one mistake. Reading it
optionally and validating in one place yields one error and one code.

**Keys are trimmed and capped at 200 characters.** Trimming means
`"key"` and `"key "` are the same retry rather than two orders. The cap keeps an
index entry bounded and is enforced in both the service and the check constraint.

**`saveAndFlush` rather than `save`.** `save` defers the insert to commit, where a
unique violation surfaces as an opaque commit failure after the method has returned —
too late to translate into a useful response. Flushing inside the method turns that
race into a clean `409`.

**A `CheckoutResult` record rather than inferring the status in the controller.**
Whether an order was created or replayed is knowledge the service has and the
controller needs; returning it explicitly beats having the controller guess from,
say, comparing timestamps.

**`Idempotent-Replay: true` in addition to the 200.** The status alone tells a
careful client what happened; the header says it in a way that is hard to miss and
easy to assert on.

**The 6A behaviour was not redesigned.** The cart lock, the conditional inventory
decrement, the snapshots and the rollback are untouched. This phase adds a lookup
before the work and a column on the result.

## Verified

- `./mvnw clean test` — **35 tests pass**, 6 new: checkout with a key succeeds;
  retry with the same key returns the same order; retry does not decrement inventory
  twice; retry does not create a second order; missing key rejected; blank key
  rejected; key reused for another cart rejected; and 6 concurrent attempts create
  exactly one order.
- **The concurrency test detects a regression.** Removing the replay lookup made it
  fail with `[no attempt should error]`, and the retry test fail with
  `expected:<200> but was:<409>`. Restored afterwards. A concurrency test that passes
  on the first run is worth distrusting until it has been shown to fail for the right
  reason.
- **Not flaky** — three consecutive clean runs of the checkout class, 14/14 each.
- Live against Supabase:
  - no header → `400 IDEMPOTENCY_KEY_REQUIRED`; blank header → the same
  - first checkout → `201`, inventory `48 → 46`
  - retry with the same key → `200`, `Idempotent-Replay: true`, same order id
    `0eb744db-...`, inventory still `46`
  - same key against another cart → `409 IDEMPOTENCY_KEY_REUSED`
  - different key against the checked-out cart → `409 CART_NOT_OPEN`
- In the database: exactly one order for the retried cart, the legacy backfill
  applied, and both new constraints rejecting direct bad writes.

### A defect found while writing this document

The race path constructed `IdempotencyKeyReuseException(key, null, cartId)`, so a
client losing that race would have been told the key was "already used to check out
cart **null**". The owning cart genuinely is not readable from a transaction that is
being rolled back, so the fix was a second constructor that names only what is known:
"is already in use by another checkout". Tests re-run, 35 passing.

Worth recording because the tests did not catch it — that path is only reached by a
same-key-different-cart race, which none of them provoke. Writing the explanation was
what surfaced it.

## Open items

- **Keys are global, not scoped to a client.** Two unrelated customers picking
  `checkout-1` would collide, and the second would get a confusing
  `IDEMPOTENCY_KEY_REUSED`. In a system with authentication the key should be unique
  per `(client, key)`, which is a change to the constraint rather than to the design.
- **Keys never expire.** They live as long as their order, which is correct for
  auditing but means the unique index grows forever. A real system would either
  accept that or move keys to a table with a retention policy — the second source of
  truth then becomes a deliberate trade rather than an accident.
- **A slow checkout blocks its retries.** The loser waits on the cart lock for as
  long as the winner's transaction runs, bounded only by the Hikari connection
  timeout. Acceptable for a checkout that touches a handful of rows; a payment call
  inside that transaction would change the calculation.
- **The multi-cart oversell race is still unproven.** N threads checking out
  *different* carts that each want the last units of one product exercises the
  conditional decrement rather than idempotency. The mechanism is in place from 6A
  and single-threaded tests cover it, but nothing yet proves it under contention.
- **Row-level security is still disabled** on all five tables.

## Next phase

**Coupons**, which is where the remaining assessment requirements sit: an
administrator can request generation, a coupon exists only if the order milestone
has been reached and has not already been rewarded, a coupon may be redeemed exactly
once, and a coupon must not be consumed by a checkout that ultimately fails.

Most of that is the same shape as this phase. Milestone generation needs "at most one
coupon per milestone", which is a unique constraint on the milestone number, not a
check-then-insert. Single redemption needs a conditional update — `set redeemed... where
id = ? and redeemed_at is null` — and it must happen inside the checkout transaction
so that a rollback returns the coupon along with the stock.

The genuinely new work is money: a percentage discount is the first place rounding can
occur, so the rule needs stating and testing — and `discount_total_cents` and
`net_total_cents`, which have been sitting at 0 since 6A, finally get populated.
