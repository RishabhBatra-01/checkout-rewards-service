# Store — checkout and rewards service

Backend for an e-commerce store: customers build a cart, check out, and every *N*th
successful order makes a percentage-discount coupon available.

The interesting part is not the CRUD — it is that the service behaves predictably when
requests are retried, when customers compete for the last unit of stock, and when two
checkouts reach for the same coupon. Those guarantees are enforced by PostgreSQL
constraints and conditional updates, not by application-level locks.

Design rationale, invariants and trade-offs are in **[DECISIONS.md](DECISIONS.md)**.
Per-phase build notes are in [`docs/`](docs/).

---

## What it does

- **Catalogue** — five seeded products, one deliberately scarce (3 units).
- **Carts** — create, view, add items, change quantity, remove items. Priced totals.
  Adding to a cart reserves no stock.
- **Checkout** — one transaction: verify the cart, take stock, snapshot prices onto
  order lines, optionally redeem a coupon, mark the cart checked out. Any failure rolls
  all of it back.
- **Idempotency** — checkout requires an `Idempotency-Key`; a retry returns the
  original order instead of creating a second one.
- **Rewards** — an administrator generates a coupon once an order milestone is reached;
  coupons are redeemable exactly once.
- **Reporting** — a read-only admin report that reconciles with the orders and coupons
  the API returns.

## Architecture

Single Spring Boot service, feature-packaged, over one PostgreSQL database.

```
com.uniblox.store
  product/    catalogue + the conditional inventory decrement
  cart/       cart and cart items
  checkout/   the checkout transaction and idempotency
  order/      immutable orders and their snapshot lines
  coupon/     reward configuration, generation, redemption
  report/     read-only admin report (plain SQL, no entities)
  web/        one @RestControllerAdvice mapping domain errors to RFC 7807
```

- **Controllers stay thin**; business rules live in `CartService`, `CheckoutService`,
  `CouponService`, each owning a transaction boundary.
- **Money is `long` integer cents** throughout. No `float`, `double` or `BigDecimal`.
- **Correctness lives in the database** — unique constraints, check constraints,
  conditional `UPDATE ... WHERE`, and one `SELECT ... FOR UPDATE` on the cart row. The
  service runs correctly as multiple instances with no code change.

---

## Requirements

| | |
|---|---|
| **Java 21** | required to build and run |
| **A Docker-compatible runtime** | required for the tests only (Testcontainers) |
| **A PostgreSQL database** | `docker compose up -d` provides one; any PostgreSQL works. Verified against PostgreSQL 17 |

Maven is not required — the repository includes the Maven wrapper (`./mvnw`).

If Java 21 came from Homebrew it is keg-only, so point `JAVA_HOME` at it first:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Configure the database

The service needs a PostgreSQL. The quickest way is the bundled one:

```bash
docker compose up -d          # starts PostgreSQL on localhost:5432
cp backend/.env.example backend/.env
```

`.env.example` already matches those container settings, so there is nothing to edit.
The application creates its schema and seeds the catalogue on first start — no dump to
import.

`.env` is git-ignored, and nothing in this repository contains credentials. Spring
imports it on startup and real environment variables take precedence, so the same build
runs unchanged in a deployed environment.

### Using a hosted PostgreSQL instead

Any PostgreSQL works — put your own values in `.env`. Two things catch people out with
Supabase specifically, and both are noted in `.env.example`:

- **It must be a JDBC URL** (`jdbc:postgresql://…`), not the `postgresql://…` URI the
  dashboard shows, and credentials go in `DB_USERNAME` / `DB_PASSWORD` rather than in
  the URL.
- **Use the session pooler (port 5432), not the transaction pooler (6543).** Flyway
  takes a session-level advisory lock while migrating and Hibernate uses server-side
  prepared statements; both misbehave behind a transaction pooler.

Development and tests used PostgreSQL 17.

## Run

```bash
./mvnw spring-boot:run
```

Serves on `http://localhost:8080`. Flyway applies pending migrations at startup, so a
fresh database is created and seeded automatically.

```bash
curl -s localhost:8080/actuator/health   # {"status":"UP", ... "db":{"status":"UP"}}
curl -s localhost:8080/api/products      # the seeded catalogue
```

`/actuator/health` is the only actuator endpoint exposed.

> **Stopping it:** `spring-boot:run` forks a child JVM, so `Ctrl-C` on Maven can leave
> it holding port 8080. If that happens:
> `pkill -f "com.uniblox.store.StoreApplication"`.

## Run the tests

```bash
./mvnw test
```

**69 tests.** Every test runs against a real PostgreSQL 17 container started by
Testcontainers — never an in-memory database, because H2 cannot reproduce the row
locking and isolation behaviour the concurrency tests depend on.

| | |
|---|---|
| JUnit 5 + Spring Boot Test + MockMvc | API-level integration tests |
| Testcontainers `postgres:17-alpine` | one throwaway database per run |
| AssertJ | assertions |

Five tests are genuinely concurrent — competing checkouts for the last unit, racing
retries of one idempotency key, two administrators generating one milestone, two
checkouts on one coupon, and reversed-order multi-product carts. Four of them
instrument thread overlap and fail if the requests do not actually run at the same
time.

Docker Desktop needs no extra setup. **Colima** needs three settings:

| Setting | Where | Why |
|---|---|---|
| `docker.host=unix://$HOME/.colima/default/docker.sock` | `~/.testcontainers.properties` | Testcontainers only probes `/var/run/docker.sock`, which Colima does not create |
| `api.version=1.44` | `~/.docker-java.properties` | Colima ships Docker Engine 29 (minimum API 1.40); docker-java otherwise negotiates 1.32 and is rejected |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` | environment | Ryuk bind-mounts the socket by its path *inside* the VM; read from the environment only |

---

## API

Base URL `http://localhost:8080`. All money values are integer cents.

### Customer

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/products` | seeded catalogue with prices and inventory |
| `POST` | `/api/carts` | `201` + `Location`; creates an empty `OPEN` cart |
| `GET` | `/api/carts/{cartId}` | items, line totals and subtotal |
| `POST` | `/api/carts/{cartId}/items` | `{"productId": 1, "quantity": 2}` — adds, or increases an existing line |
| `PATCH` | `/api/carts/{cartId}/items/{productId}` | `{"quantity": 5}` — replaces the quantity |
| `DELETE` | `/api/carts/{cartId}/items/{productId}` | removes the line |
| `POST` | `/api/carts/{cartId}/checkout` | **requires `Idempotency-Key` header**; optional body `{"couponCode": "..."}` |
| `GET` | `/api/orders/{orderId}` | the order and its snapshot lines |

### Administrative

Everything under `/api/admin` is administrative. **Authentication and authorisation are
intentionally out of scope**, so the path prefix is the only thing marking that
boundary — these endpoints are not protected.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/admin/coupons/generate` | issues the coupon for the next unrewarded milestone, if earned |
| `GET` | `/api/admin/report` | read-only; quantities by product, gross/discount/net revenue, coupon counts, order count |

### Checkout responses

| Situation | Status |
|---|---|
| First request with a key | `201 Created` + `Location` |
| Retry with the same key and coupon | `200 OK` + `Idempotent-Replay: true`, the original order |
| Same key, different cart or coupon | `409 IDEMPOTENCY_KEY_REUSED` |

### Errors

Every failure is an RFC 7807 problem document with a stable `code`, because several
different conditions share a status:

```json
{ "type": "about:blank", "title": "Insufficient inventory", "status": 409,
  "detail": "Product 5 (Limited Edition Vinyl) has 3 in stock but 10 were requested",
  "instance": "/api/carts/afab.../checkout", "code": "INSUFFICIENT_INVENTORY" }
```

Branch on `code`, not on the status. The 15 codes are listed in
[DECISIONS.md §12](DECISIONS.md).

### Postman collection

**[`docs/store-api.postman_collection.json`](docs/store-api.postman_collection.json)** —
import via **Import → Files**. 47 requests covering every endpoint and its error cases,
with assertions that check the report reconciles and that a retry replays rather than
re-charges. `Create cart` and `Checkout` store ids into collection variables, so the
requests chain without copy-pasting UUIDs.

There is **no OpenAPI/Swagger document** — the Postman collection is the executable API
documentation instead.

---

## Database, migrations and seed data

Flyway owns the schema and runs at startup; Hibernate is set to `ddl-auto: validate`
and only checks that the mapping matches. Migrations are applied in version order and
are never edited after being applied.

| Migration | Adds |
|---|---|
| `V1__create_products.sql` | `products` **and the seed catalogue** |
| `V2__create_carts.sql` | `carts` with its `OPEN`/`CHECKED_OUT` lifecycle |
| `V3__create_cart_items.sql` | `cart_items`, one row per product per cart |
| `V4__create_orders.sql` | `orders` and `order_items` (snapshot lines) |
| `V5__add_checkout_idempotency.sql` | `orders.idempotency_key`, unique |
| `V6__create_coupons.sql` | `coupons`, one per reward milestone |
| `V7__coupon_redemption.sql` | redemption state and the totals-reconcile constraint |

**Seed data ships in `V1`**, so any fresh database is usable immediately — no separate
seeding step. Five products, including *Limited Edition Vinyl* with only **3 units**,
which is what the oversell tests contend over.

### Reward configuration

`backend/src/main/resources/application.yml`:

```yaml
store:
  rewards:
    order-interval: 5     # every 5th successful order earns a coupon
    discount-percent: 10  # worth 10% off
```

Validated at startup, so an invalid value fails the boot rather than the first request.

---

## Frontend

A thin Next.js client lives in `frontend/`. It is optional — the backend is complete
and independently testable without it — but it demonstrates the full flow in a browser.

```bash
cd frontend
npm install
npm run dev          # http://localhost:3000
```

Run the backend first; the frontend is a client, not a replacement for it.

| Route | Purpose |
|---|---|
| `/` | Catalogue, add to cart |
| `/cart` | Quantities, removal, optional coupon, checkout |
| `/orders/[orderId]` | Order confirmation with gross / discount / net |
| `/admin` | Generate a reward coupon, view the report |

**The browser never calls the backend directly.** `next.config.ts` rewrites `/api/*`
to `http://localhost:8080/api/*`, so the app is same-origin and **the backend needs no
CORS configuration**. Override the target with `BACKEND_URL` if the backend runs
elsewhere.

Two details worth knowing:

- **Checkout sends an `Idempotency-Key`.** One key is generated per checkout attempt
  and reused for retries, so a request that timed out but succeeded replays the
  original order instead of creating a second one. Editing the coupon starts a new
  attempt and issues a new key.
- **A generated coupon code is shown once.** There is no endpoint to list coupons, so
  the admin page displays a new code prominently and keeps the most recent one in
  `localStorage`.

There is no authentication, so the cart id in `localStorage` is the only notion of
identity.

## Out of scope

Deliberately not implemented, and not stubbed to look implemented:

- **Authentication and authorisation** — admin endpoints are unprotected.
- **Real payment** — a successful checkout is treated as payment success. There is no
  payment gateway, fake or otherwise.
- **OpenAPI/Swagger** — see above. The Postman collection is the API documentation.

Everything else deferred, and why, is in
[DECISIONS.md §16–§17](DECISIONS.md).

---

## Repository layout

```
DECISIONS.md                        design decisions, invariants, trade-offs
docs/                               per-phase build notes + Postman collection
docker-compose.yml                  a local PostgreSQL for running the service
backend/
  pom.xml, mvnw                     Maven wrapper — no global Maven needed
  .env.example                      connection template (.env is git-ignored)
  src/main/java/com/uniblox/store/  application code, packaged by feature
  src/main/resources/
    application.yml                 config; credentials come from the environment
    db/migration/                   7 Flyway migrations
  src/test/java/com/uniblox/store/  9 test classes
frontend/
  next.config.ts                    /api/* proxy to the backend
  src/lib/                          typed API client, money formatter, storage
  src/app/                          products, cart, order, admin routes
```
