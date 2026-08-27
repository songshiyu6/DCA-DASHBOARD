ALTER TABLE investment_transaction
    ADD COLUMN ledger_order BIGINT;

WITH numbered AS (
    SELECT id,
           row_number() OVER (ORDER BY trade_date ASC, created_at ASC, id ASC) AS order_number
    FROM investment_transaction
)
UPDATE investment_transaction AS tx
SET ledger_order = numbered.order_number
FROM numbered
WHERE tx.id = numbered.id;

ALTER TABLE investment_transaction
    ALTER COLUMN ledger_order SET NOT NULL;

CREATE UNIQUE INDEX uq_transaction_ledger_order
    ON investment_transaction (ledger_order);
