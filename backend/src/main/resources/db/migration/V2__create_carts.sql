-- A cart is the unit of work a customer builds up before checkout.
--
-- `status` gives the cart an explicit lifecycle. Checkout will move a cart from
-- OPEN to CHECKED_OUT, and storing that transition on the row -- rather than
-- inferring it from the existence of an order -- is what will later let a single
-- conditional UPDATE reject a second, concurrent, or retried checkout.
--
-- The status is text with a check constraint rather than a PostgreSQL enum type:
-- adding a state later is an ordinary migration instead of an ALTER TYPE.
create table carts (
    id         uuid        primary key,
    status     text        not null check (status in ('OPEN', 'CHECKED_OUT')),
    created_at timestamptz not null
);
