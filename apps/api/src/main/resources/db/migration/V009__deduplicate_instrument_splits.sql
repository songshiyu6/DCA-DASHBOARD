ALTER TABLE instrument_split
    DROP CONSTRAINT uq_instrument_split;

WITH duplicate_rows AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY instrument_id, effective_date
               ORDER BY CASE source
                            WHEN 'YAHOO' THEN 0
                            WHEN 'TWELVE_DATA' THEN 1
                            WHEN 'ALPHA_VANTAGE' THEN 2
                            ELSE 3
                        END,
                        created_at ASC,
                        id ASC
           ) AS duplicate_number
    FROM instrument_split
)
DELETE FROM instrument_split AS split
USING duplicate_rows
WHERE split.id = duplicate_rows.id
  AND duplicate_rows.duplicate_number > 1;

ALTER TABLE instrument_split
    ADD CONSTRAINT uq_instrument_split UNIQUE (instrument_id, effective_date);
