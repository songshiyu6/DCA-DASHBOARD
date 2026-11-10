# Transaction deletion and recovery

The transaction page requires confirmation showing the symbol, date, type, quantity/price and amount before removing a ledger event. The event is excluded from active holdings, cash and plan projections after removal.

Migration V028 adds a durable recycle bin. A PostgreSQL trigger saves the complete original row in the same database transaction as every deletion. A failed ledger validation rolls back the deletion and the archive together. Archive rows have no cascading dependency on the active transaction and are retained without automatic expiry.

`GET /api/v1/transactions/trash` lists pending deleted records, newest first. `POST /api/v1/transactions/trash/{archiveId}/restore` locks the archive entry, restores the original transaction ID, ledger order, dates, exact numeric values, notes, import metadata and plan/contribution association, validates the active ledger, invalidates affected snapshots and rebuilds today's snapshot. Only a successful restore marks the entry restored. Repeating a restore returns 404; identifier/constraint conflicts return 409 and retain the archive entry. Both endpoints use the existing authentication and CSRF rules.

Open **Transactions → Recycle bin → Restore** to recover an entry. A restore can fail if subsequent account changes make the original record inconsistent; its archive remains available. This mechanism covers USD transaction ledger deletions after V028 is deployed. Older hard deletions and China-fund one-time-purchase deletions are outside this recycle bin.

The legacy QQQM transaction deleted on 2026-11-10 at 09:07:07 Asia/Shanghai was recovered separately from the 2026-10-25 SQL backup: BUY 10 shares on 2026-09-08 at USD 295.90, zero fees, original ID `d029a9d9-47e8-490f-bcbd-20d36c97a7bb`, contribution DCA, cycle 2026-09. Only that row was reinserted; newer account changes were preserved. A fresh backup was made before restoration and derived snapshots from the trade date onward were invalidated.

Validation: 264 backend tests, 13 real PostgreSQL tests (including exact recovery, duplicate restore rejection and rollback on constraint failure), and 146 frontend tests passed. Frontend lint and TypeScript/production build passed. Host Gradle bootJar succeeded. For deployment, a remote Gradle plugin TLS download failure was avoided by packaging the verified host-built jar and web dist over the existing runtime images; no credentials enter the build context.
