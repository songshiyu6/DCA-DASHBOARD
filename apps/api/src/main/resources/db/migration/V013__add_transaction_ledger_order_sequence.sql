CREATE SEQUENCE transaction_ledger_order_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1;

SELECT setval(
    'transaction_ledger_order_seq',
    COALESCE(MAX(ledger_order), 1),
    COALESCE(MAX(ledger_order), 0) > 0
)
FROM investment_transaction;

ALTER TABLE investment_transaction
    ALTER COLUMN ledger_order SET DEFAULT nextval('transaction_ledger_order_seq');

ALTER SEQUENCE transaction_ledger_order_seq
    OWNED BY investment_transaction.ledger_order;
