-- Idempotency for checkout.
--
-- The key lives on `orders` rather than in a separate ledger table, because an
-- order already *is* the record that a checkout happened: a cart produces at most
-- one order, and that order is what a retry must be given back. A second table
-- would need to be kept in step with this one, and could disagree with it.
--
-- The unique constraint is the concurrency guard. Two requests carrying the same
-- key cannot both insert, whichever application instance they land on.
alter table orders add column idempotency_key text;

-- Orders placed before idempotency existed still need a value for the NOT NULL.
-- Deriving it from the id keeps every row unique and makes the origin obvious.
update orders set idempotency_key = 'legacy-' || id::text where idempotency_key is null;

alter table orders alter column idempotency_key set not null;

alter table orders
    add constraint orders_idempotency_key_unique unique (idempotency_key);

-- A blank key would let every "unkeyed" retry collide with the first one.
alter table orders
    add constraint orders_idempotency_key_not_blank
    check (length(btrim(idempotency_key)) between 1 and 200);
