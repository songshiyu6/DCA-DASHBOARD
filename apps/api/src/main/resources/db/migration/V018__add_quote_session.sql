ALTER TABLE market_quote_latest
    ADD COLUMN quote_session VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE market_quote_latest
    ALTER COLUMN quote_session DROP DEFAULT;

ALTER TABLE market_quote_latest
    ADD CONSTRAINT ck_market_quote_latest_session CHECK (
        quote_session IN ('REGULAR', 'PRE_MARKET', 'EXTENDED', 'POST_MARKET', 'OVERNIGHT', 'REGULAR_FALLBACK', 'UNKNOWN')
    );
