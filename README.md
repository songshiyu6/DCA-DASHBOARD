# DCA Terminal v1

Personal ETF investing and DCA execution dashboard.

## What this project does

DCA Terminal is a focused single-user terminal for long-term US ETF investors:

- market quotes, five years of locally cached daily history, ETF profile data,
  performance metrics, and data freshness;
- a transaction ledger for BUY, SELL, DIVIDEND, and FEE records, including
  fractional shares;
- current holdings calculated from transactions with split-aware FIFO lots;
- monthly budget and target allocation plans with frozen plan cycles;
- actual-versus-planned DCA progress and contribution-first recommendations.

Transactions are the source of truth. Holdings, portfolio values, allocation,
P/L, XIRR, cycle execution, and daily snapshots are calculated projections.
There is no editable holdings state and no portfolio update endpoint.

This is not a broker, order-entry tool, trading chart clone, research product,
or financial advice service. v1 deliberately excludes broker APIs, options,
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
│   ├── Caddyfile
│   ├── .env.example
│   └── scripts/             # PostgreSQL backup and restore
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── market-data.md
│   └── calculations.md
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
expected baseline is Node.js 22 for the web app and Java 21 for the API. The
host Java version does not need to be Java 21 when Docker or the Gradle wrapper
is used.

## Configuration

Create the untracked deployment environment file:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set at least `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, and
`APP_PASSWORD_HASH`. The password hash is supplied by the application
implementation; do not put a plaintext password in Git. If a bcrypt hash
contains `$`, quote the value in `deploy/.env`, for example
`APP_PASSWORD_HASH='$2b$...'`, because the backup scripts source that file.

`TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` are optional provider
fallback keys. They are read only by the API container. They are never Vite
variables, frontend metadata, API response fields, logs, or GitHub Actions
secrets.

The default timezone and market schedule are `America/New_York`; database
timestamps remain UTC. `APP_TIMEZONE` configures the application and is also
used as the container `TZ`. `VITE_API_BASE_URL=/api/v1` keeps the production
web app same-origin behind Caddy. Compose defaults PostgreSQL to
`postgres:18.6-alpine`.

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

- web `npm ci`, lint when available, test, and production build;
- API Gradle test and build with Java 21;
- `docker compose config --quiet` using generated CI-only placeholder values;
- repository checks for the required application Dockerfiles and build files.

The CI job does not use repository secrets and does not call live market-data
providers. Provider tests must use mock HTTP responses. The API checks include
a separate Docker-backed Testcontainers job that starts `postgres:18.6-alpine`
and proves Flyway migrations plus Hibernate `validate` against PostgreSQL.
The required financial
unit-test surface is documented in `docs/calculations.md` and includes YTD,
CAGR, drawdown, split, FIFO, P/L, XIRR, cycle status, allocation, and
recommendation rounding.

Run the same checks locally once app files are present:

```bash
cd apps/web && npm ci && npm audit --omit=dev && npm test -- --run && npm run build
cd ../api && ./gradlew test build
cd ../.. && docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

The web project currently does not define a `lint` script; CI runs lint only if
one is added later.

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
3. All financial values use decimal/`BigDecimal` types; never use `double` for
   money, shares, weights, or rates.
4. Historical data is persisted locally and updated incrementally. Performance
   uses adjusted close where documented; portfolio valuation uses raw market
   close and historical transaction replay.
5. Provider access goes through a registry with bounded retry and fallback;
   API keys never reach the frontend.

Detailed contracts and formulas live in:

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Market data](docs/market-data.md)
- [Calculations](docs/calculations.md)
