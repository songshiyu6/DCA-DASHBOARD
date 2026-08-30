ALTER TABLE investment_plan
    ADD COLUMN initial_capital NUMERIC(20, 6);

ALTER TABLE investment_plan
    ADD CONSTRAINT chk_investment_plan_initial_capital_non_negative
    CHECK (initial_capital IS NULL OR initial_capital >= 0);

ALTER TABLE investment_transaction
    ADD COLUMN contribution_type VARCHAR(16),
    ADD COLUMN contribution_plan_id UUID REFERENCES investment_plan(id) ON DELETE SET NULL;

ALTER TABLE investment_transaction
    ADD CONSTRAINT chk_investment_transaction_contribution_type
    CHECK (contribution_type IS NULL OR contribution_type IN ('INITIAL', 'DCA', 'UNPLANNED'));

CREATE INDEX idx_investment_transaction_contribution_plan
    ON investment_transaction(contribution_plan_id, contribution_type)
    WHERE contribution_plan_id IS NOT NULL;
