-- Historical portfolio snapshots are only authoritative when they represent a
-- fresh regular-session valuation. Older builds could persist weekend rows,
-- and PARTIAL/STALE snapshots can otherwise mask a later ledger + daily-close replay.
DELETE FROM portfolio_snapshot
WHERE data_status <> 'FRESH'
   OR EXTRACT(ISODOW FROM snapshot_date) IN (6, 7);
