-- Introduce account cash as a first-class ledger projection without creating a mutable cash-balance table.
-- Existing BUY/SELL rows historically represented implicit money entering/leaving the account. Bridge those
-- implicit flows into explicit DEPOSIT/WITHDRAWAL rows so the economic meaning of legacy portfolios is preserved.

ALTER TABLE investment_transaction
    DROP CONSTRAINT ck_transaction_type,
    DROP CONSTRAINT ck_transaction_financial_fields,
    DROP CONSTRAINT chk_investment_transaction_contribution_source_consistency;

ALTER TABLE investment_transaction
    ALTER COLUMN instrument_id DROP NOT NULL;

ALTER TABLE investment_transaction
    ADD CONSTRAINT ck_transaction_type
        CHECK (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'FEE', 'DEPOSIT', 'WITHDRAWAL', 'INTEREST')),
    ADD CONSTRAINT ck_transaction_financial_fields CHECK (
        (transaction_type IN ('BUY', 'SELL') AND quantity IS NOT NULL AND unit_price IS NOT NULL)
        OR (transaction_type IN ('DIVIDEND', 'FEE') AND amount IS NOT NULL)
        OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'INTEREST') AND amount IS NOT NULL AND amount > 0)
    ),
    ADD CONSTRAINT ck_transaction_instrument_contract CHECK (
        (transaction_type IN ('BUY', 'SELL', 'DIVIDEND') AND instrument_id IS NOT NULL)
        OR transaction_type = 'FEE'
        OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'INTEREST') AND instrument_id IS NULL)
    ),
    ADD CONSTRAINT chk_investment_transaction_contribution_source_consistency CHECK (
        (
            transaction_type = 'BUY'
            AND (
                (plan_cycle_id IS NULL AND contribution_type IS NULL AND contribution_plan_id IS NULL)
                OR (plan_cycle_id IS NOT NULL AND contribution_type = 'DCA' AND contribution_plan_id IS NULL)
                OR (plan_cycle_id IS NULL AND contribution_type = 'INITIAL' AND contribution_plan_id IS NOT NULL)
                OR (plan_cycle_id IS NULL AND contribution_type = 'UNPLANNED' AND contribution_plan_id IS NULL)
            )
        )
        OR
        (
            transaction_type = 'DEPOSIT'
            AND plan_cycle_id IS NULL
            AND (
                (contribution_type IS NULL AND contribution_plan_id IS NULL)
                OR (contribution_type IN ('INITIAL', 'DCA') AND contribution_plan_id IS NOT NULL)
                OR (contribution_type = 'UNPLANNED' AND contribution_plan_id IS NULL)
            )
        )
        OR
        (
            transaction_type NOT IN ('BUY', 'DEPOSIT')
            AND plan_cycle_id IS NULL
            AND contribution_type IS NULL
            AND contribution_plan_id IS NULL
        )
    );

-- Make deterministic gaps around the legacy ledger order so the synthetic external-flow row is replayed
-- immediately before a BUY and immediately after a SELL. No user-entered transaction is reordered relative
-- to another user-entered transaction.
DROP INDEX uq_transaction_ledger_order;

UPDATE investment_transaction
SET ledger_order = ledger_order * 3;

INSERT INTO investment_transaction (
    id, instrument_id, plan_cycle_id, contribution_type, contribution_plan_id,
    transaction_type, trade_date, quantity, unit_price, amount, fee, currency, notes,
    import_batch_id, import_fingerprint, ledger_order, created_at, updated_at
)
SELECT
    (md5(tx.id::text || ':cash-ledger:deposit'))::uuid,
    NULL,
    NULL,
    tx.contribution_type,
    CASE
        WHEN tx.contribution_type = 'DCA' THEN cycle.plan_id
        ELSE tx.contribution_plan_id
    END,
    'DEPOSIT',
    tx.trade_date,
    NULL,
    NULL,
    (tx.quantity * tx.unit_price) + tx.fee,
    0,
    tx.currency,
    'System-generated legacy cash bridge for BUY ' || tx.id::text,
    NULL,
    NULL,
    tx.ledger_order - 1,
    tx.created_at,
    tx.updated_at
FROM investment_transaction tx
LEFT JOIN investment_plan_cycle cycle ON cycle.id = tx.plan_cycle_id
WHERE tx.transaction_type = 'BUY'
  AND ((tx.quantity * tx.unit_price) + tx.fee) > 0;

-- A normal legacy SELL implicitly removed its net proceeds from the account. Make that withdrawal explicit.
INSERT INTO investment_transaction (
    id, instrument_id, plan_cycle_id, contribution_type, contribution_plan_id,
    transaction_type, trade_date, quantity, unit_price, amount, fee, currency, notes,
    import_batch_id, import_fingerprint, ledger_order, created_at, updated_at
)
SELECT
    (md5(tx.id::text || ':cash-ledger:withdrawal'))::uuid,
    NULL,
    NULL,
    NULL,
    NULL,
    'WITHDRAWAL',
    tx.trade_date,
    NULL,
    NULL,
    (tx.quantity * tx.unit_price) - tx.fee,
    0,
    tx.currency,
    'System-generated legacy cash bridge for SELL ' || tx.id::text,
    NULL,
    NULL,
    tx.ledger_order + 1,
    tx.created_at,
    tx.updated_at
FROM investment_transaction tx
WHERE tx.transaction_type = 'SELL'
  AND ((tx.quantity * tx.unit_price) - tx.fee) > 0;

-- Defensive handling for the unusual case where a legacy SELL fee exceeded gross proceeds. Such a sale
-- reduced account cash; pair it with an external deposit of the same amount to preserve legacy semantics.
INSERT INTO investment_transaction (
    id, instrument_id, plan_cycle_id, contribution_type, contribution_plan_id,
    transaction_type, trade_date, quantity, unit_price, amount, fee, currency, notes,
    import_batch_id, import_fingerprint, ledger_order, created_at, updated_at
)
SELECT
    (md5(tx.id::text || ':cash-ledger:sell-fee-deposit'))::uuid,
    NULL,
    NULL,
    NULL,
    NULL,
    'DEPOSIT',
    tx.trade_date,
    NULL,
    NULL,
    abs((tx.quantity * tx.unit_price) - tx.fee),
    0,
    tx.currency,
    'System-generated legacy cash bridge for negative SELL proceeds ' || tx.id::text,
    NULL,
    NULL,
    tx.ledger_order + 1,
    tx.created_at,
    tx.updated_at
FROM investment_transaction tx
WHERE tx.transaction_type = 'SELL'
  AND ((tx.quantity * tx.unit_price) - tx.fee) < 0;

CREATE UNIQUE INDEX uq_transaction_ledger_order
    ON investment_transaction (ledger_order);

SELECT setval(
    'transaction_ledger_order_seq',
    COALESCE(MAX(ledger_order), 1),
    COALESCE(MAX(ledger_order), 0) > 0
)
FROM investment_transaction;

-- Snapshot market_value now means total portfolio value. Existing snapshots predate cash semantics and cannot
-- be mixed safely with rebuilt history, so invalidate them once and rebuild from the ledger on demand.
ALTER TABLE portfolio_snapshot_daily
    ADD COLUMN securities_value NUMERIC(20, 6),
    ADD COLUMN cash_balance NUMERIC(20, 6);

DELETE FROM portfolio_snapshot_daily;

ALTER TABLE portfolio_snapshot_daily
    ALTER COLUMN securities_value SET NOT NULL,
    ALTER COLUMN cash_balance SET NOT NULL;

COMMENT ON COLUMN portfolio_snapshot_daily.market_value IS
    'Total portfolio value: securities_value + cash_balance';
COMMENT ON COLUMN portfolio_snapshot_daily.net_cash_flow IS
    'Cumulative external cash flow: DEPOSIT - WITHDRAWAL';
