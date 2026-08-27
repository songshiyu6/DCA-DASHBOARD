WITH duplicate_rows AS (
    SELECT id,
           row_number() OVER (PARTITION BY import_fingerprint ORDER BY created_at ASC, id ASC) AS duplicate_number
    FROM investment_transaction
    WHERE import_fingerprint IS NOT NULL
)
UPDATE investment_transaction AS tx
SET import_fingerprint = NULL
FROM duplicate_rows
WHERE tx.id = duplicate_rows.id
  AND duplicate_rows.duplicate_number > 1;

ALTER TABLE investment_transaction
    DROP CONSTRAINT uq_transaction_import;

CREATE UNIQUE INDEX uq_transaction_import_fingerprint
    ON investment_transaction (import_fingerprint)
    WHERE import_fingerprint IS NOT NULL;
