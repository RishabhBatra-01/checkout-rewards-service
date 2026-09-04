-- One row per distinct product in a cart.
--
-- The unique constraint is the invariant: a cart can never hold the same product
-- in two rows, so "add a product already in the cart" is an increment rather than
-- a duplicate. Enforcing it in the database means it holds even if two requests
-- race, or if something other than this application writes to the table.
--
-- No inventory is touched here. A cart item is an intention to buy, not a claim on
-- stock; inventory is decremented at checkout.
create table cart_items (
    id         bigserial primary key,
    cart_id    uuid    not null references carts (id) on delete cascade,
    product_id bigint  not null references products (id),
    quantity   integer not null check (quantity > 0),
    constraint cart_items_one_row_per_product unique (cart_id, product_id)
);
