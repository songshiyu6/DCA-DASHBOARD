CREATE TABLE investment_transaction (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    plan_cycle_id UUID,
    transaction_type VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    quantity NUMERIC(20, 8),
    unit_price NUMERIC(20, 6),
    amount NUMERIC(20, 6),
    fee NUMERIC(20, 6) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    notes VARCHAR(1_000),
    import_batch_id UUID,
    import_fingerprint VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_transaction_type CHECK (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'FEE')),
    CONSTRAINT ck_transaction_quantity CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT ck_transaction_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT ck_transaction_amount CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_transaction_fee CHECK (fee >= 0),
    CONSTRAINT ck_transaction_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_transaction_financial_fields CHECK (
        (transaction_type IN ('BUY', 'SELL') AND quantity IS NOT NULL AND unit_price IS NOT NULL)
        OR (transaction_type IN ('DIVIDEND', 'FEE') AND amount IS NOT NULL)
    ),
    CONSTRAINT uq_transaction_import UNIQUE (import_batch_id, import_fingerprint)
);

CREATE INDEX ix_transaction_trade_date ON investment_transaction (trade_date, id);
CREATE INDEX ix_transaction_instrument_date ON investment_transaction (instrument_id, trade_date, id);
CREATE INDEX ix_transaction_cycle ON investment_transaction (plan_cycle_id);
