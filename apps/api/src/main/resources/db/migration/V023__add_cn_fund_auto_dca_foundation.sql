-- Add the first China mutual-fund and rule-driven automatic DCA foundation.
-- Automatic DCA rules are source facts. Daily purchases derived from NAV history are projections,
-- not investment_transaction rows.

ALTER TABLE instrument
    DROP CONSTRAINT ck_instrument_type,
    DROP CONSTRAINT ck_instrument_currency;

ALTER TABLE instrument
    ADD CONSTRAINT ck_instrument_type
        CHECK (instrument_type IN ('ETF', 'MUTUAL_FUND')),
    ADD CONSTRAINT ck_instrument_currency
        CHECK (currency IN ('USD', 'CNY'));

CREATE TABLE fund_profile (
    instrument_id UUID PRIMARY KEY REFERENCES instrument(id) ON DELETE CASCADE,
    management_fee_rate NUMERIC(12, 8) NOT NULL DEFAULT 0,
    confirmation_trading_days INTEGER NOT NULL DEFAULT 1,
    share_class VARCHAR(16),
    calendar_code VARCHAR(32) NOT NULL DEFAULT 'CN_FUND',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fund_management_fee_rate CHECK (
        management_fee_rate >= 0 AND management_fee_rate <= 1
    ),
    CONSTRAINT ck_fund_confirmation_days CHECK (
        confirmation_trading_days >= 0 AND confirmation_trading_days <= 10
    )
);

CREATE TABLE auto_dca_rule (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id) ON DELETE CASCADE,
    amount NUMERIC(20, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    frequency VARCHAR(32) NOT NULL DEFAULT 'DAILY_FUND_TRADING_DAY',
    start_date DATE NOT NULL,
    end_date DATE,
    purchase_fee_rate NUMERIC(12, 8) NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_auto_dca_amount CHECK (amount > 0),
    CONSTRAINT ck_auto_dca_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_auto_dca_frequency CHECK (frequency = 'DAILY_FUND_TRADING_DAY'),
    CONSTRAINT ck_auto_dca_date_range CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_auto_dca_purchase_fee_rate CHECK (
        purchase_fee_rate >= 0 AND purchase_fee_rate <= 1
    )
);

CREATE INDEX ix_auto_dca_rule_instrument_dates
    ON auto_dca_rule (instrument_id, start_date, end_date);

COMMENT ON TABLE auto_dca_rule IS
    'Source-of-truth rules for synthetic automatic DCA projections; rules do not create ledger transactions';
COMMENT ON COLUMN fund_profile.management_fee_rate IS
    'Annual fund management fee metadata only; published NAV already reflects accrued fund expenses';
COMMENT ON COLUMN fund_profile.confirmation_trading_days IS
    'Number of subsequent fund trading days used to present share-confirmation timing; NAV date remains the purchase valuation date';
