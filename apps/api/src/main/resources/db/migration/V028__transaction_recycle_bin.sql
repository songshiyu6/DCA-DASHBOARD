CREATE TABLE transaction_recycle_bin (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id uuid NOT NULL,
    record jsonb NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    restored_at timestamptz
);
CREATE INDEX transaction_recycle_bin_pending ON transaction_recycle_bin (deleted_at DESC) WHERE restored_at IS NULL;

-- Archive in the same transaction as deletion, including identifiers and exact decimals.
-- Validation failures roll back both the removal and its archive entry.
CREATE FUNCTION archive_deleted_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO transaction_recycle_bin (transaction_id, record) VALUES (OLD.id, to_jsonb(OLD));
    RETURN OLD;
END;
$$;
CREATE TRIGGER investment_transaction_recycle
AFTER DELETE ON investment_transaction
FOR EACH ROW EXECUTE FUNCTION archive_deleted_transaction();
