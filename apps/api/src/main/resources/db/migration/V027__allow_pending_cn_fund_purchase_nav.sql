-- A China mutual-fund order can be recorded before the purchase-date NAV is published.
-- NAV and shares become immutable purchase facts once that exact NAV is available.

ALTER TABLE fund_purchase
    ALTER COLUMN nav DROP NOT NULL,
    ALTER COLUMN shares DROP NOT NULL;

COMMENT ON COLUMN fund_purchase.nav IS
    'Exact purchase-date unit NAV; null while the order is waiting for published NAV';
COMMENT ON COLUMN fund_purchase.shares IS
    'Confirmed shares calculated from net subscribed amount and purchase-date NAV; null while NAV is pending';
