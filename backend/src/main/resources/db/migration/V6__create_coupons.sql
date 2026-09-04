-- Reward coupons.
--
-- `milestone` is the ordinal of the reward: the 1st coupon is earned by the Nth
-- successful order, the 2nd by the 2Nth, and so on. Making it unique is what stops
-- a milestone being rewarded twice -- including by two administrators clicking at
-- the same moment, on different application instances. The application checks
-- first for a useful error message; the constraint is what makes it true.
create table coupons (
    id               uuid        primary key,
    code             text        not null unique,
    milestone        integer     not null unique check (milestone > 0),
    discount_percent integer     not null check (discount_percent between 1 and 100),
    status           text        not null check (status in ('AVAILABLE')),
    created_at       timestamptz not null
);
