# Phase 8 — Reward coupon generation

Earning coupons. Redemption, applying them at checkout, and reporting are not in
this phase.

## What was built

**Migration** `V6__create_coupons.sql` — the `coupons` table.

**New package `coupon`**

| File | Role |
|---|---|
| `RewardProperties` | validated configuration: `orderInterval` (N) and `discountPercent` (X) |
| `Coupon` | entity; only constructible through `forMilestone` |
| `CouponStatus` | `AVAILABLE` — the only state a coupon can be in yet |
| `CouponRepository` | lookup plus `findHighestRewardedMilestone` |
| `CouponResponse` | response record |
| `CouponService` | eligibility and generation, in one transaction |
| `CouponAdminController` | `POST /api/admin/coupons/generate` |
| `MilestoneNotReachedException` / `MilestoneAlreadyRewardedException` | the two refusals |

**Touched** — `StoreApplication` gains `@ConfigurationPropertiesScan`,
`application.yml` gains the reward rule, `ApiExceptionHandler` gains two codes.

```yaml
store:
  rewards:
    order-interval: 5
    discount-percent: 10
```

### Endpoint

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/admin/coupons/generate` | `201 Created` with the coupon | `409 MILESTONE_NOT_REACHED`, `409 MILESTONE_ALREADY_REWARDED` |

```json
{
  "id": "9689e599-bf6b-477a-ba23-3301d6a07579",
  "code": "SAVE10-DC6FFB6A6C",
  "milestone": 1,
  "discountPercent": 10,
  "status": "AVAILABLE",
  "createdAt": "2026-09-04T19:08:38.947526Z"
}
```

```json
{ "title": "Reward milestone not reached", "status": 409,
  "detail": "Reward milestone 1 needs 5 successful orders; there are 2",
  "code": "MILESTONE_NOT_REACHED" }
```

## How successful orders are counted

`orderRepository.count()` — a plain `count(*)` on `orders`.

That is sufficient because of a decision made in Phase 6A: **an order row exists
only if checkout succeeded.** A checkout that fails — empty cart, insufficient
inventory, anything — rolls back its entire transaction, so no row is left behind to
be filtered out later. There is no `status` column to exclude on, no "pending" state
to age out, and no cleanup job that could fall behind and inflate the count.

This is the payoff for the "no `status` column on `orders`" choice recorded in 6A.
Had orders been inserted first and marked successful later, "count the successful
ones" would need a predicate, and every future query would have to remember it.

Verified end to end: the count stayed put across a checkout that failed on
insufficient inventory, and the milestone remained one order short.

## How the milestone is determined

```java
nextMilestone  = max(coupons.milestone) + 1     // 0 when none has been rewarded
ordersRequired = nextMilestone * orderInterval
eligible       = successfulOrders >= ordersRequired
```

`milestone` is the **ordinal of the reward**: the 1st coupon is earned by the 5th
successful order, the 2nd by the 10th, and so on.

The assessment leaves several semantics open. Two were chosen here.

**One coupon per call, lowest unrewarded milestone first.** If an administrator has
not called in a while and three milestones are due, each call issues one coupon and
they catch up by calling again. The alternative — issue every outstanding coupon at
once — makes a single request's effect unbounded, and makes "generate exactly one
coupon if eligible" untrue. The other alternative — reward only the most recent
milestone — silently destroys coupons that were earned but never claimed.

Because milestones are awarded in sequence and never skipped, "the next one" is
simply the highest rewarded plus one. No gap-hunting, no scan.

**A repeat call reports `MILESTONE_NOT_REACHED`, not "already generated".** After
milestone 1 is rewarded at 5 orders, the next milestone needs 10, so a second call
is genuinely not yet due:

> Reward milestone 2 needs 10 successful orders; there are 5

The duplicate-prevention requirement therefore falls out of the sequential design
rather than needing a branch of its own. `MILESTONE_ALREADY_REWARDED` exists only
for the concurrent case, where two requests both decide the same milestone is due.

## How duplicate generation is prevented

```sql
milestone integer not null unique check (milestone > 0)
```

The application's read decides *which* milestone to attempt and produces a useful
message. **The constraint is what makes the invariant true.** The read cannot be the
protection, because it is not atomic with the insert that follows it.

Verified by inserting a second coupon for milestone 1 directly in `psql`, outside
the application:

```
ERROR: duplicate key value violates unique constraint "coupons_milestone_key"
```

## How concurrent admin requests are handled

Two administrators click at the same moment. Both read `max(milestone) = 0`, both
compute milestone 1, both find 5 orders, both proceed to insert.

PostgreSQL's unique index lets exactly one commit. The other's `saveAndFlush` raises
a `DataIntegrityViolationException`, which becomes `409 MILESTONE_ALREADY_REWARDED`.
Nothing is lost and nothing is duplicated: the coupon exists once, and the loser is
told plainly why it got nothing.

`saveAndFlush` rather than `save` for the same reason as Phase 6B: `save` defers the
insert to commit, where the violation surfaces after the method has returned and can
no longer be turned into a meaningful response.

No `synchronized` block, no lock object, no in-memory set of seen milestones. The
invariant has to hold across application instances, and the database is the only
thing they share — the same reasoning recorded in Phase 6B.

Measured with 5 threads released together from a `CountDownLatch`: **one coupon, one
success, four conflicts**, with the overlap gauge from Phase 7 asserting the requests
genuinely ran at the same time so the test cannot pass on a sequential run.

## Design choices

**N and X are configuration, not data.** The reward rule is an operator decision;
nothing in the requirements asks to change it at runtime or to audit its history, and
a `reward_config` table would need a "which row is current" concept that earns
nothing today. `RewardProperties` is `@Validated`, so a nonsensical interval or a
percentage outside 1–100 fails the boot rather than the first request.

The consequence, accepted deliberately: coupons already generated keep the percentage
they were created with, because it is copied onto the row. But changing the interval
later re-interprets which order count a milestone corresponds to — milestone 3 means
"15 orders" under N=5 and "30 orders" under N=10. Persisting the interval on each
coupon would fix that; it is not worth it until the rule is expected to change.

**`milestone` is an ordinal, not the order count that earned it.** Storing `5, 10,
15` would be more self-describing, but it is equally ambiguous when N changes, and
the ordinal makes "the next milestone" arithmetic trivial.

**`CouponStatus` has one value, and the check constraint allows only `AVAILABLE`.**
Adding `REDEEMED` now would ship a state nothing can set and no test can reach.
Redemption widens both in its own migration — which is exactly the flexibility the
Phase 3 decision to use `text` + check constraint rather than a PostgreSQL enum type
was chosen for.

**`201` with no `Location` header.** Checkout returns `Location` because
`GET /api/orders/{id}` exists to point at. There is no coupon-retrieval endpoint yet,
so a `Location` would point nowhere. It arrives with the endpoint that justifies it.

**Everything under `/api/admin` is administrative.** Authentication is explicitly out
of scope for the exercise, so the path prefix is the only thing marking the boundary.
It is documented rather than enforced.

**The coupon code has deliberate entropy.** `SAVE10-` plus ten hex characters from a
UUID. The code column is also unique, so a collision there would raise the same
`DataIntegrityViolationException` that the milestone collision raises — and would be
reported with the wrong message. Enough entropy makes that a non-event rather than a
race worth writing code for; see Open items.

**The test class clears `orders` and `coupons` before each test.** Milestones are
computed from a global count, so unlike the cart-scoped assertions elsewhere these
tests cannot simply scope themselves to rows they created — they need a known
starting point regardless of which classes ran first. This is the same test-isolation
trap recorded in Phase 6A, in its other form.

## Verified

- `./mvnw clean test` — **42 tests pass**, 6 new: nothing generated before the
  milestone; a coupon generated once it is reached; a failed checkout does not count
  toward it; a repeat call creates no duplicate; five concurrent calls create exactly
  one; and the coupon carries the configured percentage.
- **Mutation check** — dropping `coupons_milestone_key` makes the concurrency test
  fail on `[the milestone is rewarded exactly once] expected: 1L`, proving the test
  binds to the constraint rather than to the application's read. Restored afterwards.
- **Not flaky** — three consecutive clean runs of the coupon class.
- Live against Supabase:
  - 2 orders → `409 "Reward milestone 1 needs 5 successful orders; there are 2"`
  - 5 orders → `201`, `SAVE10-DC6FFB6A6C`, milestone 1, 10%, `AVAILABLE`
  - immediate repeat → `409` naming milestone 2 and 10 orders
- Database guards independently: a duplicate milestone rejected by the unique
  constraint, and `status = 'REDEEMED'` rejected by `coupons_status_check`.

### A verification script that hung

The first live run never finished. The application failed to start with
`java.net.SocketException: Operation timed out` reaching Supabase — transient, since
`psql` was connecting normally at that moment — and the verification script used an
unbounded `while` loop waiting for the order count to rise. With no application, the
count never moved and the loop spun until the command timed out.

The fault was the script's, not the code's: a loop waiting on an external condition
needs a bound and a failure path. Rewritten with a fixed number of orders and an
explicit "did it start?" check that exits early, it ran clean.

## Open items

- **A coupon code collision would be reported as a milestone conflict.** Both columns
  are unique, and the `catch` assumes which one fired. At ten hex characters the
  probability is on the order of 1 in 10^12 per generation, so the honest fix — check
  the constraint name on the exception — is deferred rather than unknown.
- **Changing `order-interval` retroactively re-interprets milestones.** Existing
  coupons keep their own percentage, but the meaning of milestone *n* shifts.
  Recording the interval on each coupon would pin it.
- **No way to read coupons back.** There is no `GET /api/admin/coupons`, so the only
  way to see them is the generation response or the database. Reporting needs this
  anyway and will add it.
- **Coupons have no expiry, no owner, and no scope.** Any coupon can be used by
  anyone, once redemption exists. That matches the assessment, which describes a
  coupon as simply "available", but it is a decision rather than an omission.
- **Row-level security is still disabled** on all six tables.

## Next phase

**Redemption**, which is the other half of coupons and the last of the business
requirements before reporting:

- a valid coupon may be supplied at checkout;
- it can be redeemed exactly once, including under two concurrent checkouts;
- it must not be consumed by a checkout that ultimately fails;
- the discount must be deterministic and must never make a total negative.

The concurrency shape is already familiar: a conditional
`update coupons set status = 'REDEEMED', ... where id = ? and status = 'AVAILABLE'`,
run **inside** the checkout transaction so that a rollback returns the coupon along
with the stock. That single statement covers both "only once" and "not lost on
failure".

The genuinely new problem is money. A percentage discount is the first place rounding
can occur in this system, so the rule has to be chosen explicitly, written down, and
tested — with `discount_total_cents` and `net_total_cents`, at zero since Phase 6A,
finally carrying real values.
