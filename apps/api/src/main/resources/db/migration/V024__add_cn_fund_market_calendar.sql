-- Persist confirmed China market-open dates separately from fund NAV observations.
-- Only known open dates are stored. A missing NAV on one of these dates is a data gap, not a synthetic purchase.

CREATE TABLE fund_market_calendar_day (
    id UUID PRIMARY KEY,
    calendar_code VARCHAR(32) NOT NULL,
    market_date DATE NOT NULL,
    source VARCHAR(32) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fund_market_calendar_day UNIQUE (calendar_code, market_date)
);

CREATE INDEX ix_fund_market_calendar_day_calendar_date
    ON fund_market_calendar_day (calendar_code, market_date DESC);

COMMENT ON TABLE fund_market_calendar_day IS
    'Persisted confirmed China market-open dates used for fund NAV gap audit and share-confirmation timing';
COMMENT ON COLUMN fund_market_calendar_day.market_date IS
    'A confirmed open trading date; closed dates are intentionally absent instead of inferred as open';
