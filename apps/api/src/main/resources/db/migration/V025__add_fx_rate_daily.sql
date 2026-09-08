-- Persist FX facts separately from security prices and fund NAV.
-- rate means quote-currency units for one base-currency unit; USD/CNY therefore stores CNY per 1 USD.

CREATE TABLE fx_rate_daily (
    id UUID PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    rate_date DATE NOT NULL,
    rate NUMERIC(20, 10) NOT NULL,
    source VARCHAR(32) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fx_rate_daily_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_daily_distinct_currency CHECK (base_currency <> quote_currency),
    CONSTRAINT uq_fx_rate_daily UNIQUE (base_currency, quote_currency, rate_date, source)
);

CREATE INDEX ix_fx_rate_daily_pair_date
    ON fx_rate_daily (base_currency, quote_currency, rate_date DESC, retrieved_at DESC);

COMMENT ON TABLE fx_rate_daily IS
    'Observed daily FX facts used for reporting-currency conversion; never inferred from security or fund prices';
COMMENT ON COLUMN fx_rate_daily.rate IS
    'Quote-currency units for one base-currency unit. Example: USD/CNY=7.10 means 1 USD equals 7.10 CNY';
