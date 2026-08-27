CREATE TABLE instrument (
    id UUID PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    exchange VARCHAR(32),
    currency VARCHAR(3) NOT NULL,
    instrument_type VARCHAR(16) NOT NULL,
    issuer VARCHAR(255),
    expense_ratio NUMERIC(12, 8),
    aum NUMERIC(20, 6),
    dividend_yield NUMERIC(12, 8),
    data_provider VARCHAR(32),
    tracked BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_instrument_symbol UNIQUE (symbol),
    CONSTRAINT ck_instrument_type CHECK (instrument_type IN ('ETF')),
    CONSTRAINT ck_instrument_currency CHECK (currency = 'USD')
);

CREATE INDEX ix_instrument_tracked ON instrument (tracked, symbol);
