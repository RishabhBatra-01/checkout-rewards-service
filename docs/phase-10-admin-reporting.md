# Phase 10 — Administrative reporting

The read-only report. This is the last of the business requirements.

## What was built

Four new files. No migration, no entity change, and nothing existing modified.

| File | Role |
|---|---|
| `report/ReportResponse` | response record, with nested `ProductSales` and `CouponSummary` |
| `report/ReportService` | three SQL aggregates in one read-only transaction |
| `report/ReportAdminController` | `GET /api/admin/report` |
| `report/AdminReportTest` | eight tests |

### Endpoint

| Method | Path | Success |
|---|---|---|
| `GET` | `/api/admin/report` | `200 OK` — always, including on an empty store |

```json
{
  "successfulOrderCount": 7,
  "grossRevenueCents": 147539,
  "totalDiscountsCents": 2600,
  "netRevenueCents": 144939,
  "purchasedQuantityByProduct": [
    { "productId": 1, "name": "Mechanical Keyboard", "quantityPurchased": 11, "revenueCents": 142989 },
    { "productId": 2, "name": "Wireless Mouse",      "quantityPurchased":  1, "revenueCents":   4550 }
  ],
  "coupons": { "generated": 1, "available": 0, "redeemed": 1 }
}
```

An empty store returns the same shape with zeros and an empty list, not an error.

## How the report reconciles

Three statements, each a single aggregate:

```sql
-- totals
select count(*), coalesce(sum(gross_total_cents), 0)::bigint,
       coalesce(sum(discount_total_cents), 0)::bigint,
       coalesce(sum(net_total_cents), 0)::bigint
from orders

-- by product
select oi.product_id,
       (array_agg(oi.product_name order by o.placed_at desc, oi.id desc))[1],
       sum(oi.quantity)::bigint, sum(oi.line_total_cents)::bigint
from order_items oi join orders o on o.id = oi.order_id
group by oi.product_id order by oi.product_id

-- coupons
select count(*),
       count(*) filter (where status = 'AVAILABLE'),
       count(*) filter (where status = 'REDEEMED')
from coupons
```

Verified against Supabase by reconciling the response against the raw tables by
hand:

| Figure | Report | Raw tables |
|---|---|---|
| Successful orders | 7 | 7 |
| Gross / discounts / net | 147539 / 2600 / 144939 | 147539 / 2600 / 144939 |
| Product 1 | 11 units, 142989 | 11 units, 142989 |
| Product 2 | 1 unit, 4550 | 1 unit, 4550 |
| Coupons | 1 generated, 0 available, 1 redeemed | 1 row, `REDEEMED` |

Two independent cross-checks hold, and both are asserted in the tests:

- **`net = gross − discounts`** — 147539 − 2600 = 144939. Guaranteed per row by the
  `orders_totals_reconcile` constraint added in Phase 9, so it cannot drift.
- **Per-product revenue sums to gross revenue** — 142989 + 4550 = 147539. The two
  halves of the report are computed from different tables (`order_items` and
  `orders`) and still agree, which is a stronger statement than either being
  internally consistent.

### "Successful orders" needs no filter

`from orders` is the whole of it. An order row exists only if its checkout
committed; a failed checkout rolls back in its entirety and leaves nothing behind.

This is the Phase 6A decision not to give `orders` a `status` column paying off for
the third time — first for milestone counting in Phase 8, now here. Had orders been
inserted first and marked successful later, every one of these queries would carry a
predicate that someone would eventually forget.

### Why every figure comes from a snapshot

Quantities, per-product revenue and the product label all come from `order_items`;
the money totals come from the `orders` columns frozen at checkout. Nothing joins to
`products` at all.

Proved live rather than only in tests: product 1 was repriced to 99999 and renamed
to "Renamed Keyboard" directly in Supabase, and the report came back **byte
identical** — still `Mechanical Keyboard`, still 142989. The catalogue was then
restored.

## Read-only and repeatable

`@Transactional(readOnly = true)`, a `GET`, and three `select` statements. Nothing in
the path can write.

Confirmed live: row counts across `orders / order_items / coupons / carts` were
`7 / 8 / 1 / 22` before and after fetching the report, and two consecutive calls
returned byte-identical JSON.

## Design choices

**Plain SQL through `JdbcTemplate`, not JPA.** This is a read model. The figures are
aggregates over historical snapshots, they map to no entity, and nothing here is ever
written back. Loading `Order` and `OrderItem` objects only to sum their fields would
be slower and would imply an object graph that this endpoint does not have.
Repositories still own the write side, where entities and their invariants matter.

**One row per product, labelled with the most recent name it was sold under.** The
alternative — group by `(product_id, product_name)` — is arguably more truthful, since
a renamed product really was sold under two names, but it produces two rows for one
product and makes "quantity by product" awkward to consume. The label is a
convenience; the money always comes from the individual line snapshots either way.

**Per-product `revenueCents` goes slightly beyond the requirement.** It falls out of
the same aggregate, and it is what makes the "product lines sum to gross"
cross-check possible. A report that can be checked against itself is worth more than
one that only reports.

**Four queries rather than one clever join.** Each is independently readable and
obviously correct. A report over thousands of orders does not need query-count
optimisation; if it ever does, the answer is an index or a materialised view, not a
statement nobody can review.

**`coalesce(..., 0)::bigint` on every sum.** `coalesce` is what makes an empty store
return zeros instead of nulls. The explicit cast keeps PostgreSQL's `sum(bigint)`
from arriving as `numeric` and therefore `BigDecimal` — integer cents end to end, with
no floating point anywhere in the path.

## Verified

- `./mvnw clean test` — **62 tests pass**, eight new: an empty report; multiple
  orders aggregating; per-product quantities and revenue; gross/discount/net
  reconciling with each other and with the product lines; snapshots surviving a
  reprice and rename; coupon counts from coupon state; failed checkouts excluded;
  and the report being read-only and repeatable.
- **Mutation check** — the product query was rewritten to join `products` and use
  live names and prices. `repricingAndRenamingAProductDoesNotChangeReportedHistory`
  failed with `expected:<Mechanical Keyboard> but was:<Renamed Keyboard>`. Reverted,
  re-run green.
- Live against Supabase: the reconciliation table above, the catalogue-change proof,
  the read-only row counts, and byte-identical repeat calls.

## Open items

- **The report is not transaction-consistent across its three queries.** They share
  one read-only transaction, but under `READ COMMITTED` a checkout committing midway
  could be seen by one query and not another, so the figures could momentarily
  disagree. `REPEATABLE READ` for this transaction would fix it. Not done because the
  stated requirement — repeated calls on unchanged data return the same result — holds
  as it is, but it is a real limitation under concurrent writes and the first thing I
  would change if the report were used for anything binding.
- **No date filtering, no pagination.** The report is all-time and returns a row for
  every product ever sold. Fine at this scale; a real store needs both.
- **Row-level security is still disabled** on all six tables. Supabase exposes the
  `public` schema through PostgREST, so `orders`, `order_items` and `coupons` are
  reachable with the project's anon key. Open since Phase 1, and now the most serious
  item on this list.
- **The product-lock ordering issue from Phase 7 is still open.** Two carts holding
  the same two products in opposite `cart_items.id` order can deadlock; PostgreSQL
  would abort one, which currently surfaces as a 500 rather than a retryable error.
  Sorting lines by `product_id` before taking stock would remove it.

## What is left

The business requirements are complete: products, carts, cart items, checkout,
idempotency, oversell protection, coupon generation, coupon redemption, and
reporting. Sixty-two tests, seven migrations, and every invariant that matters
enforced by a database constraint rather than by application code alone.

What remains is submission work rather than features:

- **`DECISIONS.md`** — the assessment weights it heavily. The material is spread
  across these nine phase documents: the invariants, the ambiguities and the
  semantics chosen for them, the transaction and concurrency strategy, the money and
  rounding rules, the error model, what was deliberately deferred, and how the design
  would change across multiple instances. It needs consolidating into the structure
  the assessment asks for, with an honest account of AI use — including the places
  where generated output was corrected.
- **A README refresh** — setup and run instructions now that there are seven
  migrations, a Testcontainers suite, and an API surface worth documenting.
- **Repository history** — the work is not committed yet. It should land as
  meaningful increments rather than one commit.
- **The four open items above**, in the order listed.
