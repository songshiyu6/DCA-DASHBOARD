CREATE TABLE investment_plan (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    frequency VARCHAR(16) NOT NULL,
    monthly_budget NUMERIC(20, 6) NOT NULL,
    start_date DATE NOT NULL,
    execution_start_day SMALLINT NOT NULL DEFAULT 1,
    execution_end_day SMALLINT NOT NULL DEFAULT 7,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_plan_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_plan_frequency CHECK (frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY')),
    CONSTRAINT ck_plan_budget CHECK (monthly_budget > 0),
    CONSTRAINT ck_plan_window CHECK (execution_start_day BETWEEN 1 AND 31
        AND execution_end_day BETWEEN execution_start_day AND 31),
    CONSTRAINT ck_plan_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uq_active_investment_plan ON investment_plan (status)
    WHERE status = 'ACTIVE';

CREATE TABLE investment_plan_asset (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES investment_plan(id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    target_weight NUMERIC(12, 8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_plan_asset UNIQUE (plan_id, instrument_id),
    CONSTRAINT ck_plan_asset_weight CHECK (target_weight > 0 AND target_weight <= 1)
);

CREATE TABLE investment_plan_cycle (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES investment_plan(id) ON DELETE CASCADE,
    period CHAR(7) NOT NULL,
    planned_amount NUMERIC(20, 6) NOT NULL,
    executed_amount NUMERIC(20, 6) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'UPCOMING',
    opened_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_plan_cycle_period UNIQUE (plan_id, period),
    CONSTRAINT ck_cycle_period CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_cycle_amount CHECK (planned_amount > 0 AND executed_amount >= 0),
    CONSTRAINT ck_cycle_status CHECK (status IN ('UPCOMING', 'OPEN', 'PARTIAL', 'COMPLETED', 'SKIPPED'))
);

CREATE TABLE investment_plan_cycle_asset (
    id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL REFERENCES investment_plan_cycle(id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    target_weight NUMERIC(12, 8) NOT NULL,
    planned_amount NUMERIC(20, 6) NOT NULL,
    executed_amount NUMERIC(20, 6) NOT NULL DEFAULT 0,
    CONSTRAINT uq_cycle_asset UNIQUE (cycle_id, instrument_id),
    CONSTRAINT ck_cycle_asset_weight CHECK (target_weight > 0 AND target_weight <= 1),
    CONSTRAINT ck_cycle_asset_amount CHECK (planned_amount >= 0 AND executed_amount >= 0)
);

CREATE INDEX ix_plan_cycle_period ON investment_plan_cycle (period DESC);

ALTER TABLE investment_transaction
    ADD CONSTRAINT fk_transaction_plan_cycle
    FOREIGN KEY (plan_cycle_id) REFERENCES investment_plan_cycle(id);
