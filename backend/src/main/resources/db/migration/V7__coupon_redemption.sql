-- Redemption.
--
-- A coupon becomes REDEEMED exactly once. The transition is made by a conditional
-- UPDATE inside the checkout transaction, so a checkout that later fails rolls the
-- coupon back to AVAILABLE along with the stock it was going to take.
alter table coupons add column redeemed_at timestamptz;

alter table coupons drop constraint coupons_status_check;
alter table coupons add constraint coupons_status_check
    check (status in ('AVAILABLE', 'REDEEMED'));

-- The timestamp and the status cannot disagree.
alter table coupons add constraint coupons_redeemed_at_matches_status
    check ((status = 'REDEEMED') = (redeemed_at is not null));

-- The order keeps its own copy of the coupon, for the same reason it copies product
-- names and prices: it must still explain the discount after the coupon is changed,
-- renamed, or deleted.
alter table orders add column coupon_code text;
alter table orders add column coupon_discount_percent integer;

alter table orders add constraint orders_coupon_discount_percent_range
    check (coupon_discount_percent is null
           or coupon_discount_percent between 1 and 100);

-- Either both coupon columns are set or neither is.
alter table orders add constraint orders_coupon_fields_together
    check ((coupon_code is null) = (coupon_discount_percent is null));

-- A discount can only exist because a coupon was applied.
alter table orders add constraint orders_discount_requires_coupon
    check (coupon_code is not null or discount_total_cents = 0);

-- The three money columns must agree with each other. Reporting sums these, so a row
-- that does not reconcile is a reporting bug waiting to happen.
alter table orders add constraint orders_totals_reconcile
    check (net_total_cents = gross_total_cents - discount_total_cents);
