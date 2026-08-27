ALTER TABLE instrument
    ADD COLUMN data_status VARCHAR(32);

UPDATE instrument AS item
SET data_status = CASE
    WHEN EXISTS (
        SELECT 1
        FROM market_price_daily AS price
        WHERE price.instrument_id = item.id
    ) THEN 'STALE'
    ELSE 'INSUFFICIENT_HISTORY'
END;

ALTER TABLE instrument
    ALTER COLUMN data_status SET DEFAULT 'INSUFFICIENT_HISTORY',
    ALTER COLUMN data_status SET NOT NULL;

ALTER TABLE instrument
    ADD CONSTRAINT ck_instrument_data_status
    CHECK (data_status IN ('FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE', 'INSUFFICIENT_HISTORY'));
