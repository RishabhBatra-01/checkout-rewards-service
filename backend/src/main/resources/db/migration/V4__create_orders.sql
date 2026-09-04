-- An order is the permanent financial record of a completed checkout.
--
-- `cart_id` is unique: one cart can produce at most one order. This is the last
-- line of defence behind the application's own check that a cart is still OPEN --
-- even a concurrent or retried checkout that slipped past that check cannot insert
-- a second order for the same cart.
--
-- Totals are stored rather than derived. An order is a statement of what was
-- charged, and recomputing it later from the catalogue would defeat the point.
-- `discount_total_cents` is always 0 until coupons exist; it is here so the
-- reporting figures the assessment asks for (gross, discounts, net) all come from
-- the same row and cannot disagree.
create table orders (
    id                   uuid        primary key,
    cart_id              uuid        not null unique references carts (id),
    gross_total_cents    bigint      not null check (gross_total_cents >= 0),
    discount_total_cents bigint      not null check (discount_total_cents >= 0),
    net_total_cents      bigint      not null check (net_total_cents >= 0),
    placed_at            timestamptz not null
);

-- Each line snapshots what was actually bought. The product is referenced for
-- integrity, but the name, unit price and line total are copies: renaming or
-- repricing a product must never rewrite the history of a completed order.
create table order_items (
    id               bigserial primary key,
    order_id         uuid    not null references orders (id) on delete cascade,
    product_id       bigint  not null references products (id),
    product_name     text    not null,
    unit_price_cents bigint  not null check (unit_price_cents >= 0),
    quantity         integer not null check (quantity > 0),
    line_total_cents bigint  not null check (line_total_cents >= 0),
    constraint order_items_one_row_per_product unique (order_id, product_id)
);
