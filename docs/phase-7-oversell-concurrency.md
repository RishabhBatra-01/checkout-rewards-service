# Phase 7 — Proving checkout cannot oversell

A concurrency test, and nothing else. **No production code changed.**

## What was built

One file: `src/test/java/com/uniblox/store/checkout/CheckoutConcurrencyTest.java`.

It carries the same annotations as `CheckoutApiIntegrationTest`
(`@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration.class)`),
so Spring reuses the cached context and no second PostgreSQL container starts.

The scenario: one product with `inventory = 1`, five separate carts each holding one
unit of it, five threads released together, one winner expected.

## What race this protects against

**Check-then-act**, in the one place the existing locks do not reach.

Phase 4 introduced a `SELECT ... FOR UPDATE` on the cart row, and every cart
mutation takes it. That serialises everything happening to *one* cart — but here
every buyer has their own cart. Five different cart rows means five locks that never
collide, so all five threads sail past that protection and arrive at the inventory
update simultaneously. The cart lock is irrelevant to this race, which is exactly why
the race is worth a test of its own.

The defect it guards against is the obvious implementation:

```java
Product product = products.findById(id).orElseThrow();   // all five read inventory = 1
if (product.getInventory() < quantity) throw ...;        // all five pass the check
product.setInventory(product.getInventory() - quantity); // all five write 0
```

Every buyer sees stock, every buyer gets an order, and one unit is sold five times.
The gap between the read and the write is where the inventory is lost, and no amount
of application-side care closes it — the value being checked is already stale by the
time it is checked.

## Why the current inventory update is safe

There is no gap, because there is no separate read:

```sql
update products set inventory = inventory - :quantity
 where id = :productId and inventory >= :quantity
```

The condition is evaluated by PostgreSQL as part of the write, against the row as it
exists at that moment — not against a value Java loaded earlier. The affected-row
count *is* the answer to "did I get the stock?", and `CheckoutService.takeStock`
turns 0 into `InsufficientInventoryException`.

Behind it, `check (inventory >= 0)` from V1 is an independent second layer. Selling
stock that does not exist would require both to fail.

## What PostgreSQL does when concurrent updates arrive

Five transactions target the same row. The first to reach it takes a **row-level
exclusive lock**. The other four **block** — they wait, they do not fail — until that
transaction commits or rolls back.

What happens when the lock is released is the part that matters. Under
`READ COMMITTED`, a blocked `UPDATE` does not resume against the snapshot it started
with. PostgreSQL re-fetches the newly committed version of the row and
**re-evaluates the `WHERE` clause against it** — the mechanism called `EvalPlanQual`.
So the second transaction re-tests `inventory >= 1` against `0`, matches nothing, and
reports zero rows updated.

That re-evaluation is the whole protection. It is why the losers receive a clean
"insufficient inventory" answer rather than a lost update, a serialization failure,
or a negative balance.

It is also a second place where the design depends on the isolation level being
`READ COMMITTED`, the same dependency recorded for idempotency in Phase 6B. Under
`REPEATABLE READ` the blocked transaction would abort with a serialization error
instead, which is *safe* but a different contract: the client would see a retryable
error rather than a definite "sold out".

## What the test proves

- exactly **one** buyer succeeds and **four** are turned away;
- all four failures are `InsufficientInventoryException` **specifically**;
- final inventory is exactly **0**, and no product row is below zero;
- exactly **one** order exists across all five carts;
- the winning order has exactly **one** line, quantity 1, and `replayed = false`.

The assertion on the *exception type* does double duty. A write that pushed
inventory negative would be stopped by `products_inventory_check` and would surface
as a `DataIntegrityViolationException`, not an `InsufficientInventoryException`.
Requiring the precise type is therefore also the assertion that no negative write was
even attempted — stronger than checking the final number, which cannot distinguish
"never went negative" from "went negative and was corrected".

### A weakness found in the test itself

As first written, the test would have passed just as happily if the five checkouts
had run **one after another**. One success and four rejections is the correct outcome
sequentially too, so the test proved the business rule but proved nothing about
contention — which was its entire purpose.

It now instruments the race: an `AtomicInteger` tracks how many threads are inside
`checkout` at once, and the test asserts the peak exceeded 1. If the overlap it
claims to exercise ever stops happening, it fails instead of passing for the wrong
reason.

## Mutation checks

A test that has never been seen to fail is a claim, not evidence. Two mutations were
applied and reverted.

| Mutation | Outcome |
|---|---|
| **A** — remove `and inventory >= :quantity` from the update | Test fails: the four losers now fail with `DataIntegrityViolationException` from the check constraint, not `InsufficientInventoryException` |
| **B** — mutation A, plus `drop constraint products_inventory_check` | Test fails hard: **all five checkouts succeed, five orders exist, one unit of stock sold five times** |

**Mutation A alone was not good enough**, and it is worth being explicit about why.
Removing the conditional guard did not actually cause an oversell — the check
constraint still refused the negative write, so the loser transactions rolled back
and the final counts were still one order and zero inventory. Only the error type
changed. A test asserting merely "one order, inventory zero" would have passed
against genuinely broken code, protected by a layer it was not testing.

Mutation B removes both layers and produces the real defect. The test catches it on
its first assertion:

```
[exactly one buyer gets the last unit]
Expected size: 1 but was: 5
```

Both files were restored afterwards, and `ProductRepository.java` was confirmed
byte-identical to its pre-mutation backup.

## Design choices

**The race is driven through `CheckoutService`, not through HTTP.** Testing at the
service level keeps the existing Spring context (no second container, no random
port), and the invariant under test is a database invariant — it does not become more
or less true for having passed through Tomcat. The web layer is already covered by
`CheckoutApiIntegrationTest`.

**Five buyers, matching the Hikari pool size of 5.** With more threads than
connections, the surplus would queue for a connection rather than contend for the
row, which weakens the race without making the test stronger. Five threads all hold a
connection and all reach the update.

**Assertions are made against the database**, via `JdbcTemplate` — order counts,
inventory, and a check for any negative row — rather than against returned objects.
An assertion over in-memory results would pass for an implementation that is wrong in
exactly the way that matters.

**No production change.** The instruction was to leave checkout alone unless the test
exposed a real problem. It did not, and the code is untouched: the guard added in
Phase 6A already held. The value of this phase is that the mechanism is now
demonstrated rather than asserted.

## Verified

- Full suite: **36 tests, 0 failures**.
- The concurrency test **fails for the right reason** under both mutations, and
  mutation B reproduces a genuine five-fold oversell.
- **Not flaky** — five consecutive clean runs of the concurrency test, all green,
  each including the overlap assertion.
- Production code confirmed unmodified: `diff` against the pre-mutation backup is
  empty, and the guard is present on line 24 of `ProductRepository`.

## Open items

- **Partial fulfilment is untested.** Every buyer here wants exactly one unit of one
  product. A cart wanting three units when two remain, racing another cart, exercises
  the same statement but is a different arithmetic case.
- **Multi-product deadlock ordering.** Two carts containing the same two products in
  opposite orders take the product row locks in the order the items are iterated,
  which is by `cart_items.id`. That is not a consistent global order across carts, so
  a deadlock is theoretically reachable; PostgreSQL would detect it and abort one
  transaction with a `deadlock detected` error, which currently surfaces as a 500
  rather than a retryable response. Sorting lines by `product_id` before taking stock
  would remove the possibility entirely. Not done, because it is speculative until a
  test demonstrates it.
- **The test proves overlap inside `checkout`, not interleaving inside the critical
  section.** PostgreSQL deliberately serialises the contended update — that
  serialisation *is* the protection, not a gap in the coverage — but the distinction
  is worth stating rather than overclaiming.
- **Row-level security is still disabled** on all five tables.

## Next phase

**Coupons**, the last of the business requirements: administrator-triggered
generation, one coupon per reached milestone and no more, single redemption, and no
coupon consumed by a checkout that ultimately fails.

The concurrency shapes are already familiar from this phase and 6B — a unique
constraint on the milestone rather than check-then-insert, and a conditional
`update ... where redeemed_at is null` for redemption, run inside the checkout
transaction so a rollback returns the coupon along with the stock.

The genuinely new problem is money. A percentage discount is the first place rounding
can occur, so the rule has to be chosen, written down, and tested — and
`discount_total_cents` and `net_total_cents`, at zero since Phase 6A, finally carry
real values.
