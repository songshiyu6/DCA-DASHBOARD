-- V020 introduced a midnight valuation table. Daily performance is now based on the
-- previous US regular-session close, with America/New_York used only for day rollover.
DROP TABLE IF EXISTS portfolio_daily_settlement;
