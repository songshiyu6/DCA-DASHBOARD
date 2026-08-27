ALTER TABLE investment_plan_cycle
    ALTER COLUMN period TYPE VARCHAR(7)
    USING btrim(period);
