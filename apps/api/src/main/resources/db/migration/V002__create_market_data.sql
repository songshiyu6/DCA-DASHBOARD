CREATE TABLE market_price_daily (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    trade_date DATE NOT NULL,
    open NUMERIC(20, 6),
    high NUMERIC(20, 6),
    low NUMERIC(20, 6),
    close NUMERIC(20, 6) NOT NULL,
    adjusted_close NUMERIC(20, 6),
    volume BIGINT,
    source VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_market_price_daily UNIQUE (instrument_id, trade_date, source)
);

CREATE INDEX ix_market_price_daily_instrument_date
    ON market_price_daily (instrument_id, trade_date DESC);

CREATE TABLE market_quote_latest (
    instrument_id UUID PRIMARY KEY REFERENCES instrument(id),
    price NUMERIC(20, 6),
    previous_close NUMERIC(20, 6),
    change NUMERIC(20, 6),
    change_percent NUMERIC(12, 8),
    bid NUMERIC(20, 6),
    ask NUMERIC(20, 6),
    market_timestamp TIMESTAMPTZ,
    retrieved_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    CONSTRAINT ck_quote_status CHECK (status IN ('FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE'))
);

CREATE TABLE fund_nav_daily (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    nav_date DATE NOT NULL,
    nav NUMERIC(20, 6) NOT NULL,
    source VARCHAR(32) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fund_nav_daily UNIQUE (instrument_id, nav_date, source)
);

CREATE INDEX ix_fund_nav_daily_instrument_date
    ON fund_nav_daily (instrument_id, nav_date DESC);

CREATE TABLE instrument_split (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    effective_date DATE NOT NULL,
    numerator NUMERIC(20, 8) NOT NULL,
    denominator NUMERIC(20, 8) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_instrument_split UNIQUE (instrument_id, effective_date, source),
    CONSTRAINT ck_split_ratio CHECK (numerator > 0 AND denominator > 0)
);

CREATE INDEX ix_instrument_split_instrument_date
    ON instrument_split (instrument_id, effective_date);
