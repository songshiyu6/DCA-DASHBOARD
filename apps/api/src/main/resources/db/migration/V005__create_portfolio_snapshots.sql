CREATE TABLE portfolio_snapshot_daily (
    id UUID PRIMARY KEY,
    snapshot_date DATE NOT NULL,
    market_value NUMERIC(20, 6) NOT NULL,
    cost_basis NUMERIC(20, 6) NOT NULL,
    net_cash_flow NUMERIC(20, 6) NOT NULL,
    realized_pl NUMERIC(20, 6) NOT NULL,
    unrealized_pl NUMERIC(20, 6) NOT NULL,
    dividend_income NUMERIC(20, 6) NOT NULL,
    total_fees NUMERIC(20, 6) NOT NULL,
    data_status VARCHAR(32) NOT NULL DEFAULT 'FRESH',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_portfolio_snapshot_date UNIQUE (snapshot_date),
    CONSTRAINT ck_snapshot_status CHECK (data_status IN ('FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE'))
);

CREATE TABLE app_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(1_000),
    updated_at TIMESTAMPTZ NOT NULL
);
