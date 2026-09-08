# DCA Terminal Operations Runbook

> Current Flyway chain: `V001`–`V025`.

This runbook covers the current single-host Compose deployment. Execute commands from the repository root unless noted otherwise.

## Security boundaries

- Production: `APP_SECURITY_ENABLED=true`, HTTPS, secure cookies, real BCrypt `APP_PASSWORD_HASH`.
- Never commit/print plaintext passwords, hashes, provider keys, session cookies, CSRF tokens, proxy credentials, or DB credentials.
- PostgreSQL-backed Spring Session survives normal API restart; logout invalidates server session.
- Backup/restore includes session tables; explicitly decide whether restored sessions should remain valid.
- Smoke/E2E must use isolated credentials/volumes.

## Runtime

```text
Caddy -> web
      `-> /api/* -> Spring Boot -> PostgreSQL 18.6
```

Only Caddy publishes host ports.

## First deployment

```bash
cd /opt/dca-terminal
umask 077
install -m 600 deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set at least:

```text
APP_DOMAIN
CADDY_EMAIL
POSTGRES_PASSWORD
APP_USERNAME
APP_PASSWORD_HASH
```

Review provider/scheduler settings as applicable:

```text
MARKET_SYNC_ENABLED / MARKET_SYNC_CRON
FUND_SYNC_ENABLED / FUND_SYNC_CRON
FX_SYNC_ENABLED / FX_SYNC_CRON
YAHOO_PROXY_URL
TWELVE_DATA_API_KEY
ALPHA_VANTAGE_API_KEY
EASTMONEY_*
```

Validate/start:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

## Normal upgrade

Before changing a deployed version:

1. identify exact target commit;
2. confirm working tree/deployment overrides;
3. create and verify DB backup;
4. inspect forward migration compatibility;
5. start without deleting volumes;
6. run smoke and business control checks.

Never use `docker compose down -v` as a normal upgrade command.

```bash
git status --short --branch
git rev-parse HEAD

deploy/scripts/backup-postgres.sh
latest_backup="$(find deploy/backups/daily -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
gzip -t -- "$latest_backup"

docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

## Accounting migration history

### V022 real USD cash ledger

V022 introduced:

- DEPOSIT/WITHDRAWAL/INTEREST;
- cash replay;
- cash-inclusive real USD account value;
- securities/cash snapshot columns;
- external flow = DEPOSIT/WITHDRAWAL only;
- deterministic compatibility bridge cash rows around legacy BUY/SELL;
- invalidation of pre-V022 snapshots.

Legacy bridge rows preserve prior economic meaning and must not be deleted as cleanup.

Required real USD controls after upgrade:

- legal transaction shapes;
- no FIFO oversell;
- expected cash balance;
- `marketValue = securitiesValue + cashBalance` when complete;
- `netInvested = cumulative DEPOSIT - WITHDRAWAL`;
- `/performance/portfolio` external flow model `CASH_LEDGER_DEPOSIT_WITHDRAWAL`;
- snapshots rebuild;
- holdings/contribution attribution remain consistent.

### V023–V024 China fund projection

These migrations add CNY fund metadata/rules and confirmed China open dates. They do **not** turn the real transaction ledger into CNY.

Post-upgrade controls if China funds exist:

- fund instrument currency is CNY;
- fund NAV rows have positive NAV and source provenance;
- confirmed open dates exist for synchronized periods;
- `/funds/{id}/calendar` surfaces open-day NAV gaps;
- an open day without NAV does not create derived auto-DCA execution;
- automatic-DCA rule edit semantics are understood as `REWRITE_HISTORY`.

### V025 FX / unified reporting

V025 adds `fx_rate_daily` for reporting conversion. Current convention:

```text
USD/CNY
1 USD = rate CNY
source = YAHOO:CNY=X
```

V025 does not create real CNY cash balances or manual real CNY transactions.

After upgrading, if CNY auto-DCA history exists, verify:

- Flyway reports V025 applied;
- positive USD/CNY facts exist for the required history/current period after explicit/scheduled sync;
- source/date semantics are correct;
- no FX fact is stored in security-price or fund-NAV tables;
- `GET /api/v1/reporting/multicurrency?range=ALL` reports `USD` as reporting currency;
- CNY historical external flow is translated at flow-date FX rather than current FX;
- current CNY market value uses valuation-date FX;
- Reporting external-flow model is `USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY` when CNY activity exists;
- missing/stale FX or open-day missing NAV degrades combined status to `PARTIAL` instead of fabricating a value/live endpoint;
- the original real USD Dashboard and `/performance/portfolio` remain numerically/semantically unchanged.

If no CNY activity exists, Reporting should reduce to the real USD account and should not require FX.

## Read-only transaction-shape audit

```sql
SELECT transaction_type,
       contribution_type,
       plan_cycle_id IS NOT NULL AS has_cycle,
       contribution_plan_id IS NOT NULL AS has_contribution_plan,
       instrument_id IS NOT NULL AS has_instrument,
       count(*) AS rows
FROM investment_transaction
GROUP BY transaction_type, contribution_type,
         has_cycle, has_contribution_plan, has_instrument
ORDER BY transaction_type, contribution_type,
         has_cycle, has_contribution_plan, has_instrument;
```

Do not dump notes, amounts, credentials, or full ledger rows into routine logs.

## FX operational checks

Explicit refresh:

```text
POST /api/v1/fx/usd-cny/sync
```

Read persisted facts:

```text
GET /api/v1/fx/usd-cny?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

Operational expectations:

- reporting reads local persisted FX only;
- provider failure must not delete existing FX facts;
- Reporting only carries a prior FX rate forward up to seven calendar days;
- an old rate older than that must not be treated as current conversion;
- Yahoo `CNY=X` is a reporting market rate, not an official PBOC fixing or executable bank quote.

If FX sync is failing, inspect proxy/egress/provider availability before changing data. Do not hand-edit historical FX merely to make Reporting turn green without a verified source.

## China fund operational checks

Explicit fund sync:

```text
POST /api/v1/funds/{id}/sync
```

Audit:

```text
GET /api/v1/funds/{id}/calendar?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

EastMoney is an unofficial external source. Failure should preserve existing local fund NAV/calendar facts.

## Backup verification

```bash
DCA_ENV_FILE=deploy/.env deploy/scripts/backup-postgres.sh
latest_backup="$(find deploy/backups/daily -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
test -n "$latest_backup"
gzip -t -- "$latest_backup"
stat -c '%a %n' -- "$latest_backup"
```

Keep a protected off-host copy.

## Restore drill

Restore is destructive to the target state. Stop writers and use a maintenance window.

```bash
compose=(docker compose --env-file deploy/.env -f deploy/docker-compose.yml)
"${compose[@]}" stop caddy web api
gzip -t -- "$BACKUP_FILE"
DCA_ENV_FILE=deploy/.env deploy/scripts/restore-postgres.sh --confirm "$BACKUP_FILE"
"${compose[@]}" up -d api web caddy
"${compose[@]}" ps
```

Post-restore verify:

- Flyway through expected version;
- real transaction types/counts and contribution audit;
- real cash/security/total account controls;
- real performance response/model;
- fund profiles/rules/NAV/open days if present;
- FX facts/source/date controls if present;
- unified Reporting response if CNY activity exists;
- login/session policy decision.

Repeatable isolated verification:

```bash
bash deploy/scripts/backup-restore-smoke.sh
```

## Session invalidation after restore

With API stopped and target DB explicitly confirmed:

```sql
TRUNCATE TABLE spring_session_attributes, spring_session;
```

This does not delete account/plan/market/fund/FX facts.

## PostgreSQL major upgrade

PostgreSQL 18+ uses a versioned data directory beneath `/var/lib/postgresql`. Major versions are not binary-compatible.

Use logical dump/restore into a new volume/project. Keep the old volume until restored environment passes smoke and financial controls.

## Application rollback

Application rollback does not roll back Flyway. If DB advanced beyond an older application's supported schema, restore a matching verified DB backup rather than forcing the old binary onto the new schema.

Pre-V022 binaries are not assumed safe against V022+ databases. Likewise, a pre-V025 binary should not be assumed to understand V025 reporting functionality even if unrelated real-USD endpoints still compile.

## Market/fund/FX history repair

Before material repair:

1. verify provider/proxy/key configuration;
2. create/verify backup;
3. record row/date/source/control totals;
4. fetch upstream data before replacing/upserting;
5. verify dependent calculations;
6. restore if validation fails.

Never clear history first, synthesize adjusted close, invent fund NAV for an open day, or infer FX from unrelated prices.

## CI / release evidence

CI includes:

- Web install/audit/lint/typecheck/test/build;
- API test/build;
- PostgreSQL 18.6 Flyway/Hibernate validation;
- Compose/shell checks;
- backup/restore smoke;
- temporary HTTPS deployment smoke;
- Playwright E2E;
- repository whitespace checks.

Release claims must reference a concrete successful run for the **exact target head**. Empty/stale status output is not proof.

## Deployment smoke contract

Checked-in smoke validates health, session/auth/CSRF, settings/dashboard access, logout, and stale mutation rejection.

Manual V025 release verification should additionally check:

- real USD cash-inclusive Dashboard;
- DEPOSIT then BUY behavior;
- `/api/v1/performance/portfolio` real USD flow model;
- China Funds page if configured;
- FX explicit sync/read if CNY reporting is used;
- Reporting combined value/P&L/performance/provenance;
- PARTIAL behavior when deliberately testing missing required FX/NAV in an isolated environment.

## Do not do these

- Do not use `down -v` for routine upgrades.
- Do not edit published Flyway migrations.
- Do not delete V022 bridge rows.
- Do not directly edit holdings/cash/snapshots to repair transaction truth.
- Do not treat BUY as external real USD performance flow.
- Do not treat derived CNY auto-DCA purchases as real ledger rows.
- Do not use today's FX to rewrite historical CNY contributions.
- Do not fabricate NAV/FX to hide provider gaps.
- Do not print secrets/tokens/cookies in diagnostics.
- Do not run destructive E2E/smoke against production volumes.