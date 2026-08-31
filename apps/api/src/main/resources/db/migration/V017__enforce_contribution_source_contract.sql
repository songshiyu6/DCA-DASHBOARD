-- V016 introduced contribution classification fields. A cycle is the
-- authoritative source for DCA ownership, so this legacy state is safe to
-- backfill before enforcing the cross-field contract.
UPDATE investment_transaction
SET contribution_type = 'DCA',
    contribution_plan_id = NULL
WHERE transaction_type = 'BUY'
  AND plan_cycle_id IS NOT NULL
  AND contribution_type IS NULL
  AND contribution_plan_id IS NULL;

DO $$
DECLARE
    invalid_ids TEXT;
BEGIN
    SELECT string_agg(id::text, ', ' ORDER BY id)
    INTO invalid_ids
    FROM (
        SELECT id
        FROM investment_transaction
        WHERE (
            (
                transaction_type <> 'BUY'
                AND plan_cycle_id IS NULL
                AND contribution_type IS NULL
                AND contribution_plan_id IS NULL
            )
            OR
            (
                transaction_type = 'BUY'
                AND (
                    (plan_cycle_id IS NULL AND contribution_type IS NULL AND contribution_plan_id IS NULL)
                    OR (plan_cycle_id IS NOT NULL AND contribution_type = 'DCA' AND contribution_plan_id IS NULL)
                    OR (plan_cycle_id IS NULL AND contribution_type = 'INITIAL' AND contribution_plan_id IS NOT NULL)
                    OR (plan_cycle_id IS NULL AND contribution_type = 'UNPLANNED' AND contribution_plan_id IS NULL)
                )
            )
        ) IS NOT TRUE
        ORDER BY id
        LIMIT 20
    ) invalid;

    IF invalid_ids IS NOT NULL THEN
        RAISE EXCEPTION
            'Contribution source audit failed; invalid transaction IDs (first 20): %', invalid_ids;
    END IF;
END $$;

ALTER TABLE investment_transaction
    ADD CONSTRAINT chk_investment_transaction_contribution_source_consistency
    CHECK (
        (
            transaction_type <> 'BUY'
            AND plan_cycle_id IS NULL
            AND contribution_type IS NULL
            AND contribution_plan_id IS NULL
        )
        OR
        (
            transaction_type = 'BUY'
            AND (
                (
                    plan_cycle_id IS NULL
                    AND contribution_type IS NULL
                    AND contribution_plan_id IS NULL
                )
                OR
                (
                    plan_cycle_id IS NOT NULL
                    AND contribution_type = 'DCA'
                    AND contribution_plan_id IS NULL
                )
                OR
                (
                    plan_cycle_id IS NULL
                    AND contribution_type = 'INITIAL'
                    AND contribution_plan_id IS NOT NULL
                )
                OR
                (
                    plan_cycle_id IS NULL
                    AND contribution_type = 'UNPLANNED'
                    AND contribution_plan_id IS NULL
                )
            )
        )
    );

CREATE INDEX idx_investment_transaction_unclassified_buy
    ON investment_transaction(trade_date, ledger_order, id)
    WHERE transaction_type = 'BUY'
      AND plan_cycle_id IS NULL
      AND contribution_type IS NULL
      AND contribution_plan_id IS NULL;

-- Retained for schema compatibility only. Actual initial capital is derived
-- exclusively from INITIAL BUY transactions and this column is no longer
-- mapped or exposed by the application.
COMMENT ON COLUMN investment_plan.initial_capital IS
    'Deprecated compatibility column; actual initial capital comes from classified BUY transactions';

CREATE TABLE contribution_classification_audit (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    plan_id UUID NOT NULL REFERENCES investment_plan(id) ON DELETE RESTRICT,
    transaction_id UUID NOT NULL,
    previous_type VARCHAR(16),
    previous_plan_id UUID,
    new_type VARCHAR(16) NOT NULL,
    new_plan_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_contribution_classification_audit_previous_type
        CHECK (previous_type IS NULL OR previous_type IN ('INITIAL', 'DCA', 'UNPLANNED')),
    CONSTRAINT chk_contribution_classification_audit_new_type
        CHECK (new_type IN ('INITIAL', 'UNPLANNED')),
    CONSTRAINT chk_contribution_classification_audit_new_plan
        CHECK (
            (new_type = 'INITIAL' AND new_plan_id IS NOT NULL)
            OR (new_type = 'UNPLANNED' AND new_plan_id IS NULL)
        )
);

CREATE INDEX idx_contribution_classification_audit_plan_created
    ON contribution_classification_audit(plan_id, created_at DESC, id DESC);

CREATE INDEX idx_contribution_classification_audit_transaction
    ON contribution_classification_audit(transaction_id, created_at DESC);
