# DCA Terminal Operations Runbook

> Current baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`.
> Current Flyway chain: `V001`–`V022`.

This runbook covers the current single-host Compose deployment. All commands are executed from the repository root unless noted otherwise.

## Security boundaries

- Use `APP_SECURITY_ENABLED=true`, HTTPS, secure cookies, and a real BCrypt `APP_PASSWORD_HASH` for production.
- Do not commit or print plaintext passwords, hashes, provider keys, session cookies, CSRF tokens, proxy credentials, or database credentials.
- PostgreSQL-backed Spring Session survives ordinary API restart. Logout deletes server session state.
- Backup/restore also includes session tables; after restoring an old/security-sensitive point, explicitly decide whether to invalidate restored sessions.
- Smoke tests must use temporary/test credentials and must not attach to production volumes.

## Current runtime

```text
Caddy -> web
      `-> /api/* -> Spring Boot -> PostgreSQL 18.6
```

Only Caddy publishes host ports. PostgreSQL remains internal.

## First deployment

Create the protected environment file:

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

Validate Compose without printing expanded secrets:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

Start:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

Then run the authenticated deployment smoke using temporary shell variables, not credentials embedded in command history.

## Normal upgrade

Before changing a deployed version:

1. identify the exact target commit;
2. confirm the working tree and untracked deployment overrides you intend to preserve;
3. create a verified database backup;
4. inspect migration compatibility;
5. build/start without deleting volumes;
6. run smoke and business control checks.

Never use `docker compose down -v` as a normal upgrade command.

Recommended flow:

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

## V022 upgrade notes

V022 is a meaningful accounting migration, not just a schema rename.

It introduces:

- transaction types `DEPOSIT`, `WITHDRAWAL`, `INTEREST`;
- cash ledger replay;
- cash-inclusive portfolio total value;
- `securities_value` / `cash_balance` snapshot columns;
- external flow defined by DEPOSIT/WITHDRAWAL only;
- deterministic compatibility bridge rows around legacy BUY/SELL activity;
- invalidation of pre-V022 portfolio snapshots.

### Why legacy bridge rows exist

Before V022, BUY/SELL implicitly represented cash entering/leaving the account. V022 creates explicit rows so migrated accounts preserve their previous economic meaning:

- DEPOSIT immediately before a legacy BUY;
- WITHDRAWAL immediately after positive legacy SELL proceeds;
- defensive DEPOSIT for negative legacy SELL proceeds.

Do not delete these system-generated rows simply because they were created by migration.

### Required post-V022 controls

After upgrading an existing account, verify at minimum:

- Flyway reports V022 applied successfully;
- transaction ledger contains only allowed type/field combinations;
- no invalid security FIFO oversell exists;
- cash balance matches expected historical funding/economic behavior;
- `marketValue = securitiesValue + cashBalance` for complete current summary;
- `netInvested = cumulative DEPOSIT - WITHDRAWAL`;
- performance endpoint returns the expected `externalFlowModel`;
- historical snapshots rebuild after V022 invalidation;
- current holdings and contribution attribution remain consistent.

A useful read-only transaction-shape audit is:

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

Do not dump notes, symbols, amounts, credentials, or full ledger rows into routine logs unless specifically required for a controlled investigation.

## Backup verification

Create and verify:

```bash
DCA_ENV_FILE=deploy/.env deploy/scripts/backup-postgres.sh
latest_backup="$(find deploy/backups/daily -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
test -n "$latest_backup"
gzip -t -- "$latest_backup"
stat -c '%a %n' -- "$latest_backup"
```

Keep a protected off-host copy.

## Restore drill

Restore is destructive to the target database state. Enter a maintenance window and stop application writers first.

```bash
compose=(docker compose --env-file deploy/.env -f deploy/docker-compose.yml)
"${compose[@]}" stop caddy web api
gzip -t -- "$BACKUP_FILE"
DCA_ENV_FILE=deploy/.env deploy/scripts/restore-postgres.sh --confirm "$BACKUP_FILE"
"${compose[@]}" up -d api web caddy
"${compose[@]}" ps
```

Post-restore verification must now include V022-era controls:

- Flyway version;
- transaction counts/types;
- contribution classification/audit;
- cash balance;
- security value;
- total account value;
- external net invested;
- representative performance response;
- login/session policy decision.

For repeatable isolated verification:

```bash
bash deploy/scripts/backup-restore-smoke.sh
```

## Session invalidation after restore

If restored sessions should not remain valid, with the API stopped and the target database explicitly confirmed:

```sql
TRUNCATE TABLE spring_session_attributes, spring_session;
```

This does not delete transaction/plan/market data.

## PostgreSQL major upgrade

The PostgreSQL 18+ image uses a versioned data directory under the parent mount `/var/lib/postgresql`. Major-version directories are not binary-compatible.

Use logical dump/restore into a new project/volume. Never point a new PostgreSQL major directly at an old major's data directory.

Keep the old project/volume until the restored environment passes smoke and financial control totals.

## Application rollback

Application image rollback does not roll back Flyway. If the database has already advanced to a schema an older application cannot understand, use a matching verified database restore rather than forcing the old binary onto the new schema.

V022 in particular changes transaction types and accounting semantics, so pre-V022 application rollback against a V022 database should not be assumed safe.

## Market-history repair

Before full history resync:

1. verify provider/proxy/key configuration;
2. create/verify backup;
3. record row/date/adjusted-close controls;
4. call authenticated full resync;
5. verify saved bars/splits/source and dependent metrics;
6. restore if validation fails.

Do not clear history first or synthesize adjusted values.

## CI / release evidence

The current CI workflow includes:

- Web install/audit/lint/typecheck/test/build;
- API test/build;
- PostgreSQL 18.6 `postgresTest` Flyway/Hibernate validation;
- Compose and shell checks;
- backup/restore smoke;
- temporary HTTPS deployment smoke;
- Playwright E2E;
- repository whitespace checks.

Release claims must reference a concrete run for the exact commit. An empty status API response is not proof of success.

## Deployment smoke contract

The checked-in deployment smoke exercises health, unauthenticated/authenticated session flow, CSRF/login, settings/dashboard access, logout, and rejection of stale authenticated mutation state.

After V022, manual release verification should additionally open/check:

- a cash-inclusive dashboard summary;
- transaction creation paths for at least DEPOSIT and BUY;
- `/api/v1/performance/portfolio`;
- a regular-close history response;
- contribution analysis for the active plan if one exists.

## Do not do these

- Do not use `down -v` for routine upgrades.
- Do not edit a published Flyway migration.
- Do not delete V022 legacy bridge rows as cleanup.
- Do not modify holdings/cash/snapshots directly to repair transaction truth.
- Do not treat BUY as an external performance flow post-V022.
- Do not print secrets/tokens/cookies in diagnostics.
- Do not run production smoke/E2E against temporary test scripts that delete volumes.
