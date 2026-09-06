# DECISIONS

Backend for a cart / checkout / rewards service. Java 21, Spring Boot 3.5, Spring
Data JPA, Flyway, PostgreSQL (Supabase). Tests run against a real PostgreSQL 17
container via Testcontainers.

**Approximate time spent:** Approximately 5 hours — 2 hours on September 4 (6:30 PM–8:30 PM) and 3 hours from September 4 (10:30 PM) to September 5 (1:30 AM).

**A note on the commit history:** the work was built in phases, but the repository was
initialised near the end, so the history is a small number of meaningful commits rather
than a blow-by-blow record of how it developed. Nothing has been squashed or rewritten
to look tidier than it was.

The phase-by-phase account is in [`docs/`](docs/) instead — ten write-ups covering what
was built at each stage, the alternatives that were rejected, and the defects found
along the way, including several caught in review and fixed rather than papered over.
This document is the summary.

---

## 1. Invariants, and where each is enforced

The invariants below are enforced through database constraints, atomic SQL
operations, transactions, or application-level validation where appropriate.
Application checks also exist to produce useful error messages.

| # | Invariant | Enforced by |
|---|---|---|
| 1 | Inventory never goes negative | `check (inventory >= 0)` + conditional `UPDATE ... WHERE inventory >= :qty` |
| 2 | A cart holds at most one row per product | `unique (cart_id, product_id)` on `cart_items` |
| 3 | Cart quantities are positive and bounded | `check (quantity > 0)`, `@Positive` + `@Max(1000)` on requests, and an accumulation check so repeated adds cannot overflow |
| 4 | A cart produces at most one order, and is immutable afterwards | `unique` on `orders.cart_id` + cart row lock + `status` check on checkout and on every cart mutation |
| 5 | One idempotency key produces at most one order | `unique` on `orders.idempotency_key` |
| 6 | An idempotency key is never blank | `check (length(btrim(idempotency_key)) between 1 and 200)` |
| 7 | Order totals reconcile | `check (net_total_cents = gross_total_cents - discount_total_cents)` |
| 8 | A total is never negative | `check (net_total_cents >= 0)` + clamp in `Coupon.discountOn` |
| 9 | A discount only exists with a coupon | `check (coupon_code is not null or discount_total_cents = 0)` |
| 10 | At most one coupon per reward milestone | `unique` on `coupons.milestone` |
| 11 | A coupon is redeemed at most once | conditional `UPDATE ... WHERE status = 'AVAILABLE'` |
| 12 | Coupon status and timestamp agree | `check ((status = 'REDEEMED') = (redeemed_at is not null))` |
| 13 | Only successful checkouts count as orders | an order row only exists if its transaction committed |

Invariant 13 is structural rather than a constraint: there is no `status` column on
`orders`, so "count the successful ones" is `count(*)`. A failed checkout rolls back
entirely and leaves nothing to filter out. Milestone counting (§7) and reporting
(§11) both depend on this.

---

## 2. Carts do not reserve inventory

**Decision:** adding an item to a cart touches no inventory. Stock is checked and
decremented only inside the checkout transaction.

**Alternative rejected:** reserve on add. That means every abandoned cart destroys
stock until expiry, release and compensation machinery is built — and it still would
not remove the check at checkout, because stock reserved ten minutes ago says nothing
about availability now. It adds machinery without removing the work it was meant to
replace.

**Consequence:** a cart may contain more units than exist. The first availability
error a customer sees is at checkout, which is also the only place it can be acted on.

---

## 3. Pricing is read at checkout, not at add-to-cart

**Decision:** `cart_items` stores `product_id` and `quantity` only. The cart view
shows live catalogue prices; the price is frozen onto the order line at checkout.

**Alternative rejected:** snapshot the price when the item is added. That gives the
customer a price guarantee, but a cart then becomes a pricing contract with no expiry,
and stale carts quietly sell at last month's prices.

**Consequence:** a customer may see a different price than when they added the item.
The cart never promises a price; the order does.

This answers the assessment's question about price and availability changing between
add and checkout: **the cart makes no promise about either.**

---

## 4. Checkout: one transaction, ordered deliberately

`CheckoutService.checkout` is a single `@Transactional` method:

1. Lock the cart row (`SELECT ... FOR UPDATE`).
2. Reject if not `OPEN` → `409 CART_NOT_OPEN`.
3. Return the existing order if this idempotency key already produced one (§5).
4. Reject an empty cart → `409 CART_EMPTY`.
5. Redeem the coupon, if supplied (§9).
6. Take stock for each line, **in ascending product id** (§6).
7. Build order lines in cart order, snapshotting name and price (§10).
8. Apply the discount, mark the cart `CHECKED_OUT`, insert the order.

**Any failure rolls back all of it** — stock taken for earlier lines is returned, the
coupon returns to `AVAILABLE`, no order or line survives, and the cart stays `OPEN` so
the customer can fix the problem and retry.

Two orderings are deliberate:

- **Prices are read before inventory is decremented.** `decrementInventory` is a
  native modifying query that bypasses the persistence context; reading the product
  afterwards would risk a stale cached value.
- **The coupon is redeemed before stock is taken.** If it were redeemed last, a stock
  failure would never have touched it and "a failed checkout does not consume the
  coupon" would be true for the wrong reason — untested. Redeeming first makes the
  rollback the only thing that can restore it, and the test proves it does.

---

## 5. Idempotency

**Decision:** `Idempotency-Key` is a required header on checkout. The key is stored on
`orders` with a unique constraint. A repeat with the same key returns the original
order as `200` with `Idempotent-Replay: true`; the first returns `201`.

**Alternative rejected:** a separate idempotency-ledger table. An order already *is*
the record that a checkout happened, and is exactly what a retry must be handed back.
A second table would need keeping in step and could disagree — two sources of truth
for one fact.

**Same key, different request:**

| Situation | Result |
|---|---|
| Same cart, same coupon | `200` replay of the original order |
| Same cart, different coupon | `409 IDEMPOTENCY_KEY_REUSED` |
| Different cart | `409 IDEMPOTENCY_KEY_REUSED` |
| Same cart, different key, already checked out | `409 CART_NOT_OPEN` |

Returning the original order for a different request would answer a question the
client did not ask.

**Only successful checkouts are idempotent.** A failed one records no key, so retrying
genuinely retries — correct, since the stock that was missing may now be back.

**Concurrency:** both requests block on the cart row lock. The winner commits; the
loser then acquires the lock, re-reads under `READ COMMITTED`, sees the committed
order, and replays it. Verified with 6 threads: one creator, five replays, one order.

---

## 6. Inventory concurrency and lock ordering

**Decision:** check and decrement are the same statement.

```sql
update products set inventory = inventory - :quantity
 where id = :productId and inventory >= :quantity
```

0 rows affected means insufficient stock. There is no separate read that can go stale.
Under `READ COMMITTED`, a blocked `UPDATE` re-evaluates its `WHERE` clause against the
newly committed row, so the loser gets a clean "sold out" rather than a lost update.

**Alternative rejected:** read inventory, compare in Java, write the new value. The gap
between read and write is a race that no amount of application code closes.

**Deterministic lock ordering:** stock is taken in **ascending product id**, not cart
order. Two carts holding the same products in opposite order would otherwise lock the
rows in opposite sequences and deadlock. This was found and fixed late: reverting the
sort reproduces `ERROR: deadlock detected` reliably. Order lines are still built in
cart order, so nothing observable changed.

When a coupon is supplied, the effective lock order is **cart → coupon → products by
ascending id**. Product locks are always acquired in the same global order across
checkouts.

---

## 7. Coupon milestone generation

**Decision:** `N` (orders per reward) and `X` (discount percent) are validated
configuration (`store.rewards.*`, currently 5 and 10), not database rows. `milestone`
is the ordinal of the reward — the 1st coupon is earned by the 5th order.

Eligibility is `max(milestone) + 1` versus `count(*) from orders`. One coupon per
call, lowest unrewarded milestone first.

**Alternatives rejected:** issuing every outstanding coupon at once makes a single
request's effect unbounded; rewarding only the latest milestone silently destroys
coupons that were earned but never claimed.

**Concurrency:** two administrators can both compute milestone 1. The read is not the
protection — it is not atomic with the insert. `unique (milestone)` lets exactly one
commit; the loser gets `409 MILESTONE_ALREADY_REWARDED`. Verified with 5 threads.

A repeat call sequentially returns `409 MILESTONE_NOT_REACHED`, because the *next*
milestone genuinely is not due. Duplicate prevention falls out of the design.

---

## 8. Money and rounding

**Decision:** all money is `long` integer cents. There is no `float`, `double` or
`BigDecimal` anywhere in `src/main/java`.

**Rounding rule** — the only place rounding occurs:

```java
long roundedHalfUp = (grossTotalCents * discountPercent + 50) / 100;
return Math.min(roundedHalfUp, grossTotalCents);
```

- Adding half the divisor before integer division is **round half up**.
- Rounding happens **once**, on the whole-order discount. `net = gross - discount` is
  then exact.
- The clamp makes a negative total structurally impossible; the database enforces
  `net_total_cents >= 0` independently.

**Alternative rejected:** discount each line and sum. That rounds once per line and
lets the total drift a cent per line away from "10% off the order" — not what the
customer was promised.

Pinned by tests: `100.5 → 101`, `100.4 → 100`, `101.5 → 102`, `109.89 → 110`, and 100%
→ net exactly 0.

---

## 9. Coupon redemption

**Decision:** an optional checkout body `{"couponCode": "..."}`; absent, null or blank
means no coupon and checkout behaves exactly as before coupons existed.

Redemption is a conditional update inside the checkout transaction:

```sql
update coupons set status = 'REDEEMED', redeemed_at = :now
 where id = :couponId and status = 'AVAILABLE'
```

- **Redeemed once:** 0 rows affected → `409 COUPON_ALREADY_REDEEMED`. Five concurrent
  checkouts on one coupon produce exactly one order.
- **Never lost:** because redemption happens *before* stock is taken, a checkout that
  fails on inventory has already flipped the coupon inside its transaction — only the
  rollback restores it. Verified live and in tests.
- **Unknown code** fails fast with `404 COUPON_NOT_FOUND`, before any stock moves, so
  a typo costs the customer nothing.

---

## 10. Orders are immutable snapshots

**Decision:** every order line copies `product_id`, `product_name`,
`unit_price_cents`, `quantity`, `line_total_cents`. The order copies `coupon_code` and
`coupon_discount_percent`. `product_id` is a plain column, not a JPA association — no
read path depends on the catalogue.

`line_total_cents` is stored despite being derivable: an order is a statement of what
was charged, and recomputing it later is how totals drift when a rule changes.

**Verified live:** after a checkout, the product was repriced to 99999 and renamed in
the database; the order and the report both still report the original name and price.

---

## 11. Reporting

`GET /api/admin/report` is a read model: three SQL aggregates via `JdbcTemplate` in a
`@Transactional(readOnly = true)` method. No entities are loaded and nothing is
written.

**Alternative rejected:** loading orders through JPA and summing in Java — slower, and
it implies an object graph this endpoint does not have.

Every figure comes from snapshots: quantities and per-product revenue from
`order_items`, the money totals from `orders`, coupon counts from `coupons.status`,
the order count from `count(*)`.

**It reconciles two independent ways**, both asserted in tests:

- `net = gross − discounts`, guaranteed per row by `orders_totals_reconcile`;
- per-product revenue sums to gross revenue — two figures computed from *different
  tables* that still agree.

Checked against Supabase by hand: 7 orders, 147539 / 2600 / 144939, product 1 at 11
units → 142989, product 2 at 1 → 4550, coupons 1 generated / 0 available / 1 redeemed.

---

## 12. Error model

Every failure returns an RFC 7807 problem document, produced by a single
`@RestControllerAdvice` translating domain exceptions. Services throw domain
exceptions (`CartNotFoundException`, `InsufficientInventoryException`, …) and know
nothing about HTTP.

Each response carries a stable **`code`** alongside the standard fields, because
several genuinely different conditions share a status:

```json
{ "type": "about:blank", "title": "Insufficient inventory", "status": 409,
  "detail": "Product 5 (Limited Edition Vinyl) has 3 in stock but 10 were requested",
  "instance": "/api/carts/afab.../checkout", "code": "INSUFFICIENT_INVENTORY" }
```

| Status | Meaning here | Codes |
|---|---|---|
| `400` | The request itself is malformed | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED` |
| `404` | The thing addressed does not exist | `CART_NOT_FOUND`, `CART_ITEM_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `ORDER_NOT_FOUND`, `COUPON_NOT_FOUND` |
| `409` | The request is well-formed but conflicts with current state | `CART_NOT_OPEN`, `CART_EMPTY`, `CART_ITEM_QUANTITY_LIMIT`, `INSUFFICIENT_INVENTORY`, `COUPON_ALREADY_REDEEMED`, `IDEMPOTENCY_KEY_REUSED`, `MILESTONE_NOT_REACHED`, `MILESTONE_ALREADY_REWARDED` |

**Choice:** branch on `code`, not on status. Inventing distinct status codes to
separate eight different conflicts would abuse HTTP semantics; a stable string is what
a client should switch on. 15 codes are defined.

**Detail messages name the specific thing** — which product, how much stock, which
milestone, how many orders — so a failure is actionable without reading server logs.
Validation errors name the rejected field (`"quantity must be greater than zero"`).

**Alternative rejected:** `ResponseStatusException` thrown from services. It couples
domain logic to the web layer and produces no machine-readable discriminator.

---

## 13. Why the database, not in-memory locks

A `synchronized` block or a `ConcurrentHashMap` guards **one JVM**. With two instances
behind a load balancer, two requests land on different pods that share no memory: both
pass their local guard, both proceed. It also loses state on restart, and — worst — it
tests green on a developer machine, so the defect ships.

The database is the only thing every instance shares, so every invariant lives there.
Three mechanisms, used consistently:

| Mechanism | Used for |
|---|---|
| Conditional `UPDATE ... WHERE <precondition>` | inventory, coupon redemption |
| Unique constraint | one order per cart, one order per key, one coupon per milestone, one cart row per product |
| `SELECT ... FOR UPDATE` row lock | serialising all mutations of a single cart |

Concurrency tests assert against `count(*)` in PostgreSQL rather than objects on the
heap: a heap assertion would pass for an implementation that is wrong in exactly the
way that matters.

---

## 14. Material decisions, with alternatives

| # | Decision | Alternative rejected | Why |
|---|---|---|---|
| 1 | Integer cents everywhere | `BigDecimal`, or floating point | Exact by construction; one explicit rounding point instead of accumulated drift |
| 2 | Idempotency key on `orders` | Separate idempotency table | One fact, one row; no second source of truth to drift out of step |
| 3 | Conditional `UPDATE` for stock | Read-compare-write in Java | Removes the read/write gap entirely rather than narrowing it |
| 4 | Order snapshots | Join to `products` at read time | An order must explain what was charged, not what the catalogue says today |
| 5 | Reward rule as configuration | A `reward_config` table | No requirement to change it at runtime; a table needs a "which row is current" concept that earns nothing |
| 6 | Report as plain SQL | JPA entities and Java aggregation | A read model over snapshots maps to no entity and is never written back |

---

## 15. Ambiguities, and how they were resolved

| Ambiguity | Resolution |
|---|---|
| What happens if price/availability changes between add and checkout | The cart promises neither; both are settled at checkout (§2, §3) |
| How many coupons per generate call | One, lowest unrewarded milestone first (§7) |
| What a repeat generate call returns | `409 MILESTONE_NOT_REACHED` — the next milestone genuinely is not due |
| Whether a retry should replay or conflict | Same cart *and* coupon → replay; anything else → conflict (§5) |
| Rounding direction for a percentage discount | Half up, once, on the whole-order discount (§8) |
| Whether removal is idempotent | No — a second `DELETE` returns `404`, so a client sending the wrong product id learns about it |
| What an emptied cart becomes | Still `OPEN` with a stable id; deleting it would break the client's reference |
| Where an unknown coupon fails | Before any stock moves, so a typo costs nothing |
| Which name to report for a renamed product | The name it was most recently sold under; the money always comes from each line's own snapshot |
| What counts as administrative | Everything under `/api/admin`; authentication is out of scope, so the path is the only marker |

---

## 16. Implemented vs deferred

**Implemented**

- Products with seed data, including a deliberately scarce item
- Cart create / view / add / update quantity / remove, with priced totals
- Checkout with inventory decrement, order creation, single-checkout enforcement
- Idempotent checkout with replay semantics
- Coupon generation at milestones; coupon redemption with a percentage discount
- Admin report reconciling with orders and coupons
- RFC 7807 problem responses with 15 stable machine-readable `code` values
- 7 Flyway migrations; 69 test executions including five concurrency tests

**Deliberately deferred**

| Deferred | Why |
|---|---|
| Authentication / authorisation | Explicitly out of scope |
| Real payment integration | Successful checkout is treated as payment success, as permitted |
| Cart expiry, order cancellation, refunds | No requirement; each would add a real lifecycle |
| Coupon expiry, ownership, stacking | The assessment describes a coupon as simply "available" |
| Report date filtering and pagination | All-time report is adequate at this scale |
| Retry policy for transient SQL failures | The one reachable deadlock was removed by construction (§6) |
| Row-level security on the Supabase schema | Known gap, see §17 |

---

## 17. Known limitations

1. **Row-level security is disabled** on all six tables. Supabase exposes the `public`
   schema through PostgREST, so `orders`, `order_items` and `coupons` are reachable
   with the project's anon key, outside this application entirely. **The most serious
   item here.**
2. **The report is not transaction-consistent across its three queries.** They share a
   read-only transaction, but under `READ COMMITTED` a checkout committing midway could
   be seen by one query and not another. `REPEATABLE READ` would fix it.
3. **Idempotency keys are global, not per client.** Two unrelated customers choosing
   the same key would collide. With authentication the constraint should be on
   `(client, key)`.
4. **A coupon code collision would be misreported.** Both `code` and `milestone` are
   unique and the exception handler assumes which fired. At 10 hex characters this is
   ~1 in 10¹² per generation, so it is deferred rather than unknown.
5. **A redeemed coupon can only be released by a rollback.** There is no administrative
   un-redeem; only relevant once cancellation exists.
6. **Changing `order-interval` retroactively re-interprets milestones.** Existing
   coupons keep their own percentage, but the meaning of milestone *n* shifts.
7. **No bounded retry for transient serialization failures** in general.

---

## 18. Multi-instance and production evolution

**What already works unchanged.** Every invariant is a database constraint, a
conditional update, or a row lock — none is process-local. Running N instances behind
a load balancer requires no code change: two retries landing on different pods still
produce one order, and two checkouts for the last unit still produce one sale.

**What would need attention:**

| Area | Change |
|---|---|
| Connection pool | 5 per instance is sized for one process; N instances need a pool budget against Supabase's limit, or a pooler in transaction mode with prepared statements disabled |
| Flyway on startup | Concurrent instances are safe — Flyway takes a session advisory lock — but migrations should move to a deploy step rather than racing at boot |
| Report consistency | `REPEATABLE READ`, or a read replica, once reads compete with writes |
| Transient failures | A bounded retry around transactions failing with a transient SQL state |
| Idempotency key growth | Keys live as long as their order; at scale, either accept that or move them to a table with retention |
| Observability | No metrics or tracing; contention on the cart lock and inventory conflicts are the things worth measuring |
| Schema isolation | Move application tables out of `public`, which also resolves limitation 1 |

**What would not change:** the concurrency design. It was built assuming more than one
instance from the start, which is why no part of it lives in memory.

---

## 19. AI usage

I used Claude Code throughout the assessment to help with implementation, migrations, tests, and documentation. I worked phase by phase with small, scoped prompts rather than asking AI to build the whole system at once.

I treated generated code and tests as suggestions, not as proof that the implementation was correct. For the important business invariants, I verified the behavior with integration tests, concurrency tests, mutation checks, direct PostgreSQL checks, and live Supabase verification.

### Examples where AI output needed correction or deeper verification

- **Weak concurrency test:** An initial concurrency test could have passed even if the operations executed sequentially. I added an in-flight overlap check so the test proves that contention actually occurred.
- **Weak mutation test:** Removing only the inventory guard was not enough to demonstrate overselling because the database constraint still prevented negative inventory. I changed the mutation so both protections were removed, which exposed the real failure.
- **Deadlock assumption:** The multi-product checkout deadlock initially looked theoretical. I reproduced it by reversing product lock order and observed PostgreSQL's `deadlock detected` error. I then fixed it with deterministic product lock ordering.
- **Test isolation issues:** Some generated tests relied on global database counts or leaked product state between tests. I corrected the test setup and assertions so tests are isolated.
- **Verification issues:** One live verification attempt used an unbounded loop and an orphaned application process caused stale-code verification. I diagnosed both issues, bounded the verification, and verified the actual running application before trusting the result.
- **Documentation accuracy:** I rechecked numerical and concurrency claims in the documentation against the actual repository and corrected claims that did not match what the tests proved.

The main lesson from using AI on this assessment was that generated code can look correct while the tests around it are not strong enough to detect a broken implementation. For the critical invariants, I therefore tried to prove that the tests fail when the protection is deliberately removed, rather than relying only on a green test suite.

---

## 20. With two more hours

In priority order:

1. **Enable row-level security** on all six tables, or move them out of `public`.
   It is the only limitation with a security consequence.
2. **`REPEATABLE READ` for the report transaction**, plus a test that hammers checkout
   while reporting and asserts the figures still reconcile.
3. **A bounded retry** around transactions failing with a transient SQL state, so
   correctness does not depend on having removed every cause by construction.
4. **A multi-instance test** — two application processes against one database, running
   the existing concurrency suites across both. The design claims this works; nothing
   has proven it.
5. **Scope idempotency keys to a client** once there is a notion of one.

The first two are the ones I would not want to submit without, given more time.
