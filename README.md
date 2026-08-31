# DCA Terminal

Personal ETF investing and DCA execution dashboard.

## What this project does

DCA Terminal is a focused single-user terminal for long-term US ETF investors:

- market quotes, five years of locally cached daily history, ETF profile data,
  performance metrics, and data freshness;
- a transaction ledger for BUY, SELL, DIVIDEND, and FEE records, including
  fractional shares;
- current holdings calculated from transactions with split-aware FIFO lots;
- monthly budget and target allocation plans with frozen plan cycles;
- actual-versus-planned DCA progress and contribution-first recommendations;
- explicit `INITIAL`, `DCA`, and `UNPLANNED` BUY classification, with initial
  capital separated from recurring DCA; and
- contribution-batch analysis for principal, current value, cumulative P/L,
  cumulative ROI, and cost-weighted days invested.

Transactions are the source of truth. Holdings, portfolio values, allocation,
P/L, XIRR, cycle execution, and daily snapshots are calculated projections.
There is no editable holdings state and no portfolio update endpoint.

This is not a broker, order-entry tool, trading chart clone, research product,
or financial advice service. The current product deliberately excludes broker APIs, options,
individual-stock research, cryptocurrency, news, AI advice, price prediction,
WebSocket ticks, Level 2, technical indicators, multi-user SaaS, social
features, paper trading, backtesting, multi-currency assets, and tax
calculation.

## Repository layout

```text
.
├── apps/
│   ├── web/                 # React + TypeScript + Vite (app agent)
│   └── api/                 # Spring Boot 3 + Java 21 (app agent)
├── deploy/
│   ├── docker-compose.yml   # web, api, postgres, caddy
│   ├── docker-compose.e2e.yml
│   ├── Caddyfile
│   ├── .env.example
│   └── scripts/             # PostgreSQL backup and restore
├── e2e/                     # Playwright vs mock Yahoo, isolated volumes
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── market-data.md
│   ├── calculations.md
│   ├── operations-runbook.md
│   ├── agent-handoff.md
│   └── next-development-plan.md
├── .github/workflows/ci.yml
└── README.md
```

The deployment and CI configuration uses the application-owned
`apps/web/Dockerfile` and `apps/api/Dockerfile`. The web image serves port 80;
the API image serves port 8080 and exposes `/actuator/health` for the Compose
healthcheck. The deployment layer does not modify application source files.

## Prerequisites

For the full Compose stack:

- Docker Engine with Docker Compose v2;
- a DNS A/AAAA record for the deployment hostname pointing to the host;
- inbound TCP 80 and 443 for Caddy's ACME certificate flow;
- a real `APP_PASSWORD_HASH` and database password stored only in
  `deploy/.env`.

For local app development, use the versions declared by the app projects. The
expected baseline is Node.js 22 for the web app and Java 21 for the API. Docker
provides its own build JDK, but the Gradle wrapper does not: direct
`./gradlew`/`bootRun` commands still require a Java 21 `JAVA_HOME` and `PATH`.

## Configuration

Create the untracked deployment environment file:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set at least `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, and
`APP_PASSWORD_HASH`. The current application uses Spring's
`BCryptPasswordEncoder`, so this value must be a BCrypt hash; Argon2id and
delegating `{id}` prefixes are not accepted by the current code. Generate it
outside the repository and do not put a plaintext password in Git. If the hash
contains `$`, quote the value in `deploy/.env`, for example
`APP_PASSWORD_HASH='$2b$...'`, because the backup scripts source that file.

`TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` are optional provider
fallback keys. They are read only by the API container. They are never Vite
variables, frontend metadata, API response fields, logs, or GitHub Actions
secrets.

The web runtime is `live` by default. In live mode every account, plan,
transaction, holding, and market-data value must come from the API; an API
network failure is shown as an error and is never replaced with demo data.
Set `VITE_APP_MODE=demo` only for an explicitly non-account local preview.
Demo mode reads deterministic fixture data, writes only to browser-local demo
storage, and shows a persistent `Demo data` warning. Do not use a demo build
for a real account. `apps/web/Dockerfile` rejects any mode other than `live`
or `demo`.

Business dates, plan execution windows, and the market-data scheduler use the
fixed `America/New_York` zone; database timestamps remain UTC. There is no
runtime timezone setting and no `APP_TIMEZONE` input. The container `TZ` value
is an operating-system/logging concern and does not change business-date
semantics. `VITE_API_BASE_URL=/api/v1` keeps the production web app same-origin
behind Caddy. Compose defaults PostgreSQL to `postgres:18.6-alpine`.

## PostgreSQL major-version upgrades

The named `postgres_data` volume is a PostgreSQL data directory, not a
portable database export. Compose mounts the PostgreSQL 18.6 volume at
`/var/lib/postgresql`; the image stores data below the versioned
`/var/lib/postgresql/18/docker` directory. A data directory initialized by
PostgreSQL 16 cannot be mounted directly into PostgreSQL 18.6; the newer
server will reject it as an incompatible major-version directory. Updating
`POSTGRES_IMAGE` alone is not an upgrade procedure.

For an existing PostgreSQL 16 deployment, use the checked-in logical backup
scripts:

1. Run `deploy/scripts/backup-postgres.sh` while the old stack is healthy and
   verify the dump with `gzip -t`.
2. Stop the stack with `docker compose down`, never `docker compose down -v`.
   Inspect and rename the old project volume so it remains recoverable. The
   old volume was used at the legacy `/var/lib/postgresql/data` mount; the new
   Compose file uses the PostgreSQL 18+ parent mount.
3. Set `POSTGRES_IMAGE=postgres:18.6-alpine` in `deploy/.env` and start the
   PostgreSQL service; Compose creates a new empty volume.
4. Restore the verified dump with
   `deploy/scripts/restore-postgres.sh --confirm <backup-file.sql.gz>`.
5. Start the full stack and verify Flyway, transaction counts, portfolio
   totals, healthchecks, and market-data freshness.

The volume name includes `COMPOSE_PROJECT_NAME`, so confirm the exact name with
`docker volume ls` before renaming. Keep the old volume until the new database
has passed the restore checks. The plain SQL dump is portable across these
PostgreSQL major versions.

## Development

Validate the deployment graph without starting containers:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

Start the complete stack after both app Dockerfiles exist:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
curl -fsS https://invest.example.com/api/health
```

Replace the hostname in the `curl` command with `APP_DOMAIN`. Caddy will
obtain a public certificate when DNS and ports 80/443 are correct. For a
local-only check, use `APP_DOMAIN=localhost`; Caddy will use its local
certificate authority, so a browser may need local trust setup.

Run the app projects directly when developing their source:

```bash
cd apps/web
npm ci
npm run dev
```

```bash
cd apps/api
./gradlew bootRun
```

The exact scripts belong to the app projects. The root CI workflow requires a
lockfile and the Gradle wrapper so local and CI dependency resolution stay
repeatable.

## Tests and CI

The checked-in workflow at `.github/workflows/ci.yml` runs on pushes and pull
requests. It performs:

- web `npm ci`, lint, typecheck, test, and production build;
- API Gradle test and build with Java 21;
- Testcontainers PostgreSQL 18.6 Flyway + Hibernate `validate`;
- `docker compose config --quiet` using generated CI-only placeholder values;
- PostgreSQL dump → new volume → restore smoke;
- Caddy/API deployment smoke;
- Playwright e2e smoke (PR default) against a mock Yahoo stack on
  `127.0.0.1:38080`; `workflow_dispatch` can run the full suite;
- repository checks for Dockerfiles, backup scripts, and the e2e runner.

The CI job does not use repository secrets and does not call live market-data
providers. Provider tests must use mock HTTP responses. Flyway is the only
schema source; the current chain is `V001`–`V017`. `V013` adds the atomic
ledger-order sequence, `V014` adds PostgreSQL-backed HTTP sessions, `V015`
removes the obsolete timezone setting, and `V016` adds contribution source and
plan attribution fields. `V017` backfills deterministic cycle-linked legacy
BUYs, enforces contribution-source combinations, and adds classification audit
storage. These are forward migrations and cannot be undone by rolling back only
the application image.

Current account valuations prefer the newest valid timestamped regular,
pre-market, extended, post-market, or overnight quote. Historical portfolio
charts, snapshots, YTD, and TWR remain regular-close based, so an after-hours
move changes live value/P&L without rewriting close-based performance history.

The required financial unit-test surface is documented in
`docs/calculations.md` and includes YTD, CAGR, drawdown, split, FIFO, P/L,
XIRR, cycle status, allocation, recommendation rounding, initial-capital cycle
behavior, and contribution-batch attribution.

Run the same checks locally once app files are present:

```bash
cd apps/web && npm ci && npm audit --omit=dev && npm run lint && npm run typecheck && npm test -- --run && npm run build
cd ../api && ./gradlew test build postgresTest --no-daemon
cd ../.. && docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
DCA_E2E_SUITE=smoke bash e2e/run.sh
```

E2E uses isolated Compose volumes and never attaches to the acceptance stack
on ports 80/443/18080. Install Playwright Chromium through
`http://127.0.0.1:7890` when a proxy is required, then unset proxy variables
before the tests so `127.0.0.1` is not proxied. Do not run `e2e/run.sh` from a
worktree that contains `apps/api/build`; copy the local Gradle output into the
API image context and the healthcheck can fail. A root `.dockerignore`
excludes `build` and `node_modules`.

The web project defines an ESLint-based `lint` script. CI runs lint, typecheck,
tests, and the production build as separate steps.

## Deployment

The production topology is:

```text
Internet -> Caddy (HTTPS) -> web static files
                         \-> /api/* -> Spring Boot -> PostgreSQL
```

Only Caddy publishes host ports. PostgreSQL is on an internal Docker network
and is accessed by the API and the backup scripts through `docker compose
exec`. Flyway owns schema migrations and the API must use
`spring.jpa.hibernate.ddl-auto=validate`; do not use Hibernate schema update
in production.

Deploy or update the stack:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml pull postgres caddy
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs --tail=100 api
```

Before first public exposure, verify that `/api/health` responds, the API
healthcheck is healthy, Caddy has issued the expected certificate, and the
application login works. Provider outages must leave the dashboard available
with `STALE`, `PARTIAL`, or `UNAVAILABLE` data status.

HTTP sessions are stored in PostgreSQL. An API container restart therefore
does not normally sign the user out when the same database and cookie settings
remain in use; logout deletes the server-side session. Backup/restore includes
the session tables, so operators must explicitly decide whether restored
sessions should remain valid before reopening access.

## Backups and restore

The backup script writes restrictive, compressed plain SQL files under
`deploy/backups/` by default. It keeps seven daily files and four weekly files;
the directory is ignored by Git.

```bash
deploy/scripts/backup-postgres.sh
```

Install the checked-in systemd timer on the host after adjusting the deployment
path and service user as needed:

```bash
sudo install -m 0644 deploy/systemd/dca-terminal-backup.service /etc/systemd/system/
sudo install -m 0644 deploy/systemd/dca-terminal-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dca-terminal-backup.timer
systemctl list-timers dca-terminal-backup.timer
```

The service runs the backup after the stack is started; the script itself
checks PostgreSQL readiness. A weekly file is created on `BACKUP_WEEKLY_DAY`
(default Sunday, UTC). `BACKUP_DIR`,
`BACKUP_RETENTION_DAILY`, and `BACKUP_RETENTION_WEEKLY` can be overridden in
`deploy/.env`.

Restore requires an explicit confirmation flag and first takes a safety backup
unless `DCA_RESTORE_SKIP_SAFETY_BACKUP=1` is deliberately set:

```bash
deploy/scripts/restore-postgres.sh --confirm deploy/backups/daily/dca-terminal-YYYYMMDDTHHMMSSZ.sql.gz
```

Restore replaces database objects from the selected `pg_dump`. Stop public
traffic or put the application into maintenance mode, verify the selected file
with `gzip -t`, restore, then check `/api/health`, Flyway state, transaction
count, portfolio totals, and provider freshness before reopening traffic.

## Design rules

The implementation must preserve these rules:

1. Transactions are the source of truth; never persist editable holdings as
   primary state.
2. Market price and ETF NAV are separate facts and separate tables. Never use
   market price as NAV.
3. Backend and database financial values use `BigDecimal`/`NUMERIC`; do not
   introduce Java `double` or floating-point database columns. Financial
   response values are plain decimal JSON strings, and frontend calculations
   use `decimal.js-light`. Counts and calendar-day values remain JSON numbers.
4. Historical data is persisted locally and updated incrementally. Performance
   uses adjusted close where documented; portfolio valuation uses raw market
   close and historical transaction replay.
5. Provider access goes through a registry with bounded retry and fallback;
   API keys never reach the frontend.
6. Initial capital and recurring DCA are transaction classifications, not a
   second cash ledger. Only actual classified BUY transactions contribute to
   contribution totals; unclassified and `UNPLANNED` buys remain visible but do
   not silently enter plan analytics.
7. Legacy BUY classification is a two-phase operation: preview first, then
   commit the exact preview hash atomically. Every committed row is recorded in
   the contribution-classification audit table.

Detailed contracts and formulas live in:

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Market data](docs/market-data.md)
- [Calculations](docs/calculations.md)
- [Current state and next development plan](docs/next-development-plan.md)
