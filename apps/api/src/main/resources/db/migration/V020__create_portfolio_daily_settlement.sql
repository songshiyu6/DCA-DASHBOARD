CREATE TABLE portfolio_daily_settlement (
    id UUID PRIMARY KEY,
    settlement_date DATE NOT NULL,
    settlement_at TIMESTAMPTZ NOT NULL,
    market_value NUMERIC(20, 6),
    net_cash_flow NUMERIC(20, 6) NOT NULL,
    data_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_portfolio_daily_settlement_date UNIQUE (settlement_date),
    CONSTRAINT ck_portfolio_daily_settlement_status CHECK (
        data_status IN ('FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE', 'INSUFFICIENT_HISTORY')
    )
);
