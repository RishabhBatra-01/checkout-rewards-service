-- Baseline schema: the catalogue the rest of the domain will build on.
--
-- Money is stored as integer minor units (cents) in a BIGINT so that arithmetic
-- is exact and discount rounding stays deterministic.
create table products (
    id          bigserial primary key,
    name        text    not null,
    price_cents bigint  not null check (price_cents >= 0),
    inventory   integer not null check (inventory >= 0)
);

-- Seed catalogue. "Limited Edition Vinyl" deliberately has scarce inventory so
-- oversell and concurrency behaviour can be exercised.
insert into products (name, price_cents, inventory) values
    ('Mechanical Keyboard',   12999, 50),
    ('Wireless Mouse',         4550, 120),
    ('27" 4K Monitor',        34999, 25),
    ('USB-C Docking Station', 18900, 40),
    ('Limited Edition Vinyl',  2999, 3);
