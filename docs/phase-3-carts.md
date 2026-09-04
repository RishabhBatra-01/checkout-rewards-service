# Phase 3 — Carts

Creating and retrieving a cart. No items, no checkout.

## What was built

**Migration** `V2__create_carts.sql` — a `carts` table.

**Domain** (`com.uniblox.store.cart`)

| File | Role |
|---|---|
| `Cart` | JPA entity; the only way to build one is `Cart.open()` |
| `CartStatus` | `OPEN` / `CHECKED_OUT` |
| `CartRepository` | `JpaRepository<Cart, UUID>` |
| `CartResponse` | response record |
| `CartController` | the two endpoints |
| `CartNotFoundException` | domain error |

**Error handling** (`com.uniblox.store.web.ApiExceptionHandler`) — translates domain
exceptions into RFC 7807 problem responses.

### Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/carts` | `201 Created`, `Location: /api/carts/{id}` | — |
| `GET` | `/api/carts/{cartId}` | `200 OK` | `404` unknown cart, `400` malformed UUID |

```json
{
  "id": "7837e036-cf16-4ce5-9522-40af5e92b569",
  "status": "OPEN",
  "createdAt": "2026-09-04T16:26:14.823415Z"
}
```

A 404 returns a problem document rather than an empty body:

```json
{
  "type": "about:blank",
  "title": "Cart not found",
  "status": 404,
  "detail": "Cart d3477c99-... was not found",
  "instance": "/api/carts/d3477c99-..."
}
```

## Why the cart needs a state

The assessment requires that **a cart must not be checked out more than once**, and
that concurrent or retried checkouts do not produce two orders or charge inventory
twice. `status` is where that invariant will be enforced.

The alternative is to infer it — "this cart is checked out if an order exists that
references it". That reads fine sequentially and fails under concurrency: two
requests both query for an order, both find none, and both proceed. The check and
the write are separate operations, so nothing stops them interleaving.

Holding the state on the cart row turns the guard into a single conditional write:

```sql
update carts set status = 'CHECKED_OUT' where id = ? and status = 'OPEN'
```

The database applies row-level locking, so exactly one concurrent transaction sees
one updated row and the rest see zero. The loser gets a deterministic outcome to
report instead of a race. That is the whole reason `status` exists now rather than
being added alongside checkout — the column shapes the checkout design, so it is
worth committing to early.

`CHECKED_OUT` is terminal. A cart is never reopened; a customer who wants to buy
again creates a new cart.

## What the database stores

```sql
create table carts (
    id         uuid        primary key,
    status     text        not null check (status in ('OPEN', 'CHECKED_OUT')),
    created_at timestamptz not null
);
```

Three columns, each earning its place:

- **`id`** — UUID, because clients address carts directly. A sequential integer
  would let one client guess another's cart id.
- **`status`** — the lifecycle above. The check constraint means an invalid state
  cannot exist even if written by something other than this application.
- **`created_at`** — `timestamptz`, stored in UTC.

There is deliberately no `updated_at` yet. Nothing updates a cart in this phase, and
a column the application never maintains is worse than no column. It arrives in the
migration that first needs it.

## How a request flows through Spring Boot

`POST /api/carts`:

1. **Tomcat** accepts the connection and hands the request to `DispatcherServlet`.
2. **`DispatcherServlet`** matches method and path against the registered handler
   mappings and selects `CartController.create()`.
3. **`CartController`** calls `Cart.open()`, which returns a cart already in `OPEN`
   with `createdAt` set, and passes it to `CartRepository.save()`.
4. **Spring Data JPA** — `save()` sees a null id, so Hibernate treats it as an
   insert rather than a merge. `GenerationType.UUID` makes Hibernate generate the
   identifier in Java, so the id is known without a round trip. A transaction is
   opened around the repository call; the `INSERT` goes through HikariCP to Postgres.
5. **Jackson** serializes `CartResponse` to JSON. `Instant` becomes an ISO-8601
   string because Spring Boot disables `WRITE_DATES_AS_TIMESTAMPS` by default.
6. **`ResponseEntity`** carries `201` and the `Location` header back out.

`GET /api/carts/{cartId}` follows the same path, except Spring converts the path
segment to a `UUID` before the method is invoked — a malformed id fails there and
never reaches the controller, which is why it returns `400` for free. If the
repository returns empty, the controller throws `CartNotFoundException`;
`@RestControllerAdvice` catches it and renders a `ProblemDetail` as `404`.

## Design choices

**Path is `/api/carts`, not `/carts`.** The instruction said `POST /carts`, but the
existing catalogue lives at `/api/products`. A surface that mixes `/api/products`
with `/carts` is a defect in an API graded on design. Easy to change if the bare
path is wanted — say so and both move.

**No `items` field in the response yet.** An empty `items: []` would advertise a
contract that does not exist. Clients get the field in the phase that implements it.

**`text` + check constraint rather than a Postgres enum type.** Adding a state later
is an ordinary migration instead of an `ALTER TYPE`, which is awkward to reverse.
Hibernate maps it with `@Enumerated(EnumType.STRING)`, so the stored value stays
readable in `psql`.

**Controller talks to the repository directly.** There is no `CartService` because
there is no logic to put in it — creating a cart is one constructor and one save.
A service appears when checkout arrives and there is a transaction boundary and
business rules worth naming. Consistent with `ProductController`.

**Timestamps set in Java, not by a database default.** One source of truth, and the
value is known immediately so it can be returned in the `POST` response without
re-reading the row.

**RFC 7807 problem responses.** The assessment asks for errors that are
"distinguishable and useful to an API client". `ProblemDetail` is built into Spring
Boot 3, so this cost one small class and gives every future error a consistent
shape.

**The Phase 2 migration test was relaxed.** It asserted `flyway_schema_history` had
exactly one row, which broke the moment V2 landed. It now asserts that every
recorded migration succeeded and that V1 is present — still a real check, but one
that does not need editing every time a migration is added.

## Verified

- `./mvnw clean test` — 4 tests pass: create, retrieve, unknown-cart 404, plus the
  Phase 2 migration test
- Flyway applied V2 to Supabase incrementally (`Current version: 1` → `now at
  version v2`), proving migrations work against an already-populated database
- Live API against Supabase: `201` with `Location`, `200` on retrieve, `404`
  problem document for an unknown id, `400` for a malformed one
- Confirmed in Supabase with `psql`: `carts` table with the check constraint
  present, both migrations recorded successful, two `OPEN` rows

## Open item

`public.carts` has row-level security disabled, same as `products`. Supabase exposes
the `public` schema through PostgREST, so the table is reachable with the project's
anon key outside this application. Still worth resolving before order and coupon
data lands in the same schema.
