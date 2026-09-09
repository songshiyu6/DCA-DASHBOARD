-- Persist real one-time China mutual-fund purchases separately from the USD transaction ledger.
-- The recorded NAV and shares are purchase facts and are not recomputed by later NAV synchronization.

CREATE TABLE fund_purchase (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id) ON DELETE CASCADE,
    purchase_date DATE NOT NULL,
    gross_amount NUMERIC(20, 6) NOT NULL,
    purchase_fee_rate NUMERIC(12, 8) NOT NULL DEFAULT 0,
    nav NUMERIC(20, 6) NOT NULL,
    purchase_fee NUMERIC(20, 6) NOT NULL,
    net_subscribed_amount NUMERIC(20, 6) NOT NULL,
    shares NUMERIC(28, 8) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fund_purchase_gross_amount CHECK (gross_amount > 0),
    CONSTRAINT ck_fund_purchase_fee_rate CHECK (purchase_fee_rate >= 0 AND purchase_fee_rate <= 1),
    CONSTRAINT ck_fund_purchase_nav CHECK (nav > 0),
    CONSTRAINT ck_fund_purchase_fee CHECK (purchase_fee >= 0),
    CONSTRAINT ck_fund_purchase_net_amount CHECK (net_subscribed_amount > 0),
    CONSTRAINT ck_fund_purchase_shares CHECK (shares > 0)
);

CREATE INDEX ix_fund_purchase_instrument_date
    ON fund_purchase (instrument_id, purchase_date, created_at, id);

COMMENT ON TABLE fund_purchase IS
    'Persisted one-time CNY mutual-fund purchases; separate from the real USD cash/transaction ledger';
COMMENT ON COLUMN fund_purchase.nav IS
    'Exact unit NAV captured when the purchase is recorded so later provider corrections do not rewrite acquired shares';
