# DCA Terminal Architecture

## Product boundary

DCA Terminal is a single-user, USD-first terminal for US ETFs. It combines
market data, the user's real transaction ledger, an investment plan, and the
calculated state of the portfolio. It does not place orders, connect to a
broker, provide investment advice, or emulate a trading venue.

The current non-goals are broker APIs, options, individual-stock research,
cryptocurrency, news, AI recommendations, price prediction, WebSocket ticks,
Level 2, technical indicators, multi-user SaaS, social features, paper
trading, backtesting, multi-currency assets, and tax calculations.

## System shape

The repository is a monorepo with a modular monolith API:

```text
.
├── apps/
│   ├── web/                 # React + TypeScript + Vite
│   └── api/                 # Spring Boot 3 + Java 21
├── deploy/
│   ├── docker-compose.yml
│   ├── docker-compose.e2e.yml
│   ├── Caddyfile
│   └── scripts/
├── e2e/                     # Playwright against mock Yahoo, isolated volumes
├── docs/
├── .github/workflows/ci.yml
└── README.md
```

The application runtime is:

```text
Internet
   |
   v
Caddy (TLS, :80/:443)
   |-- /       -> web:80
   `-- /api/*  -> api:8080 -> postgres:5432
```

The Compose file places PostgreSQL on an internal `backend` network. The API
joins both `backend` and `edge`; the web server and Caddy join `edge`. The
database has no published host port. Caddy is the only public entry point.

The deployment configuration builds the application-owned Dockerfiles:

- `apps/web/Dockerfile` serves the production frontend on port 80 and includes
  an HTTP health probe at `/`.
- `apps/api/Dockerfile` serves Spring Boot on port 8080, includes an HTTP health
  probe for `/actuator/health`, and runs with Java 21.

The deployment layer passes the API's `DATABASE_*`, `FLYWAY_ENABLED`,
`APP_THEME`, security enable/cookie/user/password values, `APP_SESSION_*`, and
market-provider values directly to names consumed by `application.yml`.
`APP_DOMAIN` and `CADDY_EMAIL` are Caddy-only settings. The canonical Compose
forwards the two `APP_LOGIN_*` throttle overrides shown in `.env.example`; their
defaults are 5/900. Business dates are deliberately fixed to
`America/New_York`; there is no `APP_TIMEZONE` setting.

The authenticated web application exposes six workspaces: Dashboard, Plan,
Contributions, ETFs (list and detail), Transactions, and Settings. Login and
not-found routes sit outside the workspace shell. The Contributions workspace
is active-plan scoped and reads the plan contribution-analysis projection; it
does not persist a separate portfolio or cash balance. Attributed batches are
plan-scoped, while the current unclassified-BUY queue is account-wide because
those rows do not yet carry a plan association.

## Source of truth and projections

The only authoritative business facts are:

```text
Instrument
Market price / quote
Fund NAV
Split event
Transaction
Transaction contribution source (`INITIAL`, `DCA`, `UNPLANNED`, or unclassified)
Investment plan and plan assets
```

The portfolio is a projection, not an editable fact:

```text
Transactions + split events + prices
             |
             v
      holdings and FIFO lots
             |
             v
portfolio summary, history, allocation, P/L, XIRR
             |
             `-> plan-scoped initial/DCA contribution batches
```

There is no `POST /portfolio/update-holdings` or equivalent write API. A
holding can only change after a valid transaction or a split event is applied.
Daily snapshots are rebuildable read models used to make history and the
dashboard fast; they must never become a second source of truth. The dashboard
loads today's transactions, splits, and prices once for summary, holdings, and
allocation. History keeps its own snapshot-coverage plus replay path and is
not served from that current-ledger object. There is no cross-request
in-memory portfolio cache.

Plan cycles are also derived from a plan, calendar rules, and linked BUY
transactions. A cycle stores a frozen asset allocation at creation time so a
later plan edit cannot rewrite historical intent. Its executed amount and
status are projection fields and may be recalculated.

Contribution analysis is another projection. It uses the same ordered,
split-aware transaction ledger and tags FIFO lots with their original
`INITIAL` or plan-cycle `DCA` source. The optional
`investment_plan.initial_capital` value is reference metadata only; it does not
create cash, shares, or contribution principal.

## Modules

The Spring Boot application is one deployable unit with these ownership
boundaries:

- `auth`: single-user session authentication, CSRF, and login throttling.
- `instrument`: tracked ETF identity and profile metadata.
- `marketdata`: provider SPI, registry, fallback, cache, ingestion, and data
  freshness.
- `transaction`: validation, CRUD, CSV import, duplicate detection, and
  transaction-to-cycle suggestions.
- `portfolio`: split-aware FIFO replay, holdings, P/L, XIRR, allocation, and
  snapshots.
- `observability`: low-cardinality Micrometer meters for provider calls, sync,
  snapshot invalidate/rebuild, portfolio replay, and CSV import. Allowed tag
  keys are `provider`, `operation`, `outcome`, `status`, and `mode`. Symbol,
  notes, credentials, and SQL must not be metric tags. Actuator still exposes
  only `health` and `info`.
- `plan`: plan assets, cycle lifecycle, progress, drift, and contribution
  recommendation, plus initial-versus-DCA batch analysis.
- `settings`: non-secret display and provider configuration status.
- `shared`: decimal policies, time conventions, API errors, and observability.

Controllers expose HTTP contracts. Application services coordinate use cases.
Domain services perform financial calculations. Infrastructure adapters own
JPA, provider HTTP clients, caching, and scheduling. Provider classes must not
be called directly from controllers or portfolio calculations.

## Persistent model

Flyway owns every schema change. Production uses
`spring.jpa.hibernate.ddl-auto=validate`; Hibernate must not create or alter
tables. The current published chain is `V001` through `V017`:

- `V013` adds `transaction_ledger_order_seq` and the default used for atomic
  same-day ledger ordering;
- `V014` creates PostgreSQL-backed Spring Session tables;
- `V015` removes the obsolete persisted timezone setting;
- `V016` adds the compatibility initial-capital column plus transaction-level
  contribution type and plan attribution; and
- `V017` deprecates that column at the application boundary, backfills
  deterministic cycle-linked BUYs, adds cross-field CHECK/index rules, and
  creates the contribution-classification audit table.

These are forward migrations. Schema cannot be rolled back with an older
application image; restore a matching dump when an older candidate is not
compatible with the applied chain.

The current schema contains the following logical tables:

| Table | Purpose |
| --- | --- |
| `instrument` | ETF identity, exchange, currency, issuer, and profile metadata |
| `market_price_daily` | OHLCV, raw close, adjusted close, source, and trade date |
| `market_quote_latest` | Latest regular or extended-hours quote, prior regular close, change, bid/ask, timestamp, freshness, and source |
| `fund_nav_daily` | Fund NAV by date and source; never a market price alias |
| `instrument_split` | Effective-date split ratios from a provider |
| `investment_transaction` | BUY, SELL, DIVIDEND, and FEE facts, ledger order, and BUY contribution attribution |
| `investment_plan` | Budget, frequency, execution window, dates, and status; the old nullable `initial_capital` column is compatibility-only |
| `investment_plan_asset` | Target weight per instrument |
| `investment_plan_cycle` | Frozen monthly intent and aggregate execution projection |
| `investment_plan_cycle_asset` | Frozen cycle-level target weights and execution projection |
| `portfolio_snapshot_daily` | Rebuildable daily summary and data status |
| `app_setting` | Non-secret application settings and provider status |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | Persistent authenticated HTTP session state |
| `contribution_classification_audit` | Batch/transaction IDs and before/after source attribution for confirmed legacy classification |

`investment_transaction` is used instead of a table named `transaction` to
avoid SQL keyword ambiguity. The API still calls the resource
`/transactions`.

Quantities use `NUMERIC(20,8)`. Prices, monetary amounts, and costs use
`NUMERIC(20,6)`. Weights use `NUMERIC(12,8)`. Java calculations use
`BigDecimal`; Java `double` and database floating-point columns are forbidden
for financial values. `JacksonConfig` serializes response `BigDecimal` values
as plain decimal JSON strings. Counts and calendar-day values remain numbers.
Controller and Web-normalizer regressions cover full `NUMERIC(20,6/8)` boundary
values without passing through a JavaScript number. Timestamps
are stored in UTC. Trading dates and the monthly cycle period are date values, interpreted using
`America/New_York` for market and schedule decisions.

Database constraints include unique instrument symbols, unique provider keys
for daily prices/NAV/splits, unique plan-plus-instrument assets, and at most
one active plan. The application validates that target weights total 100% with
a tolerance of 0.0001 (0.01 percentage points). The database constrains each
individual weight to `(0, 1]`, but the cross-row 100% sum is currently enforced
by the application rather than a database trigger.

For contribution attribution, V017 adds the mechanically expressible
cross-field CHECK across `transaction_type`, `plan_cycle_id`,
`contribution_type`, and `contribution_plan_id`, plus a partial index for the
legacy-null BUY queue. It deterministically backfills cycle-linked/null-source
BUYs to DCA and aborts with the first invalid transaction IDs if other legacy
combinations violate the new contract. Cross-table rules such as initial BUY
date equaling plan start date remain enforced by the service and integration
tests. The published V016 file remains unchanged.

## PostgreSQL image and volume compatibility

Compose defaults to `postgres:18.6-alpine` and mounts its named volume at
`/var/lib/postgresql`, the parent directory used by the PostgreSQL 18+ image.
The image's default `PGDATA` is `/var/lib/postgresql/18/docker`. PostgreSQL
data directories are major-version specific: a directory initialized by
PostgreSQL 16 (or another major version) must not be mounted directly into
PostgreSQL 18.6. The new server rejects the directory as incompatible;
changing only the image tag does not perform a database upgrade.

Upgrade an existing deployment with a logical dump and restore:

1. While the old PostgreSQL container is healthy, run
   `deploy/scripts/backup-postgres.sh` and verify the resulting file with
   `gzip -t`.
2. Stop the stack without `docker compose down -v`; keep the old volume.
3. Preserve or rename the old `postgres_data` volume, switch
   `POSTGRES_IMAGE` to `postgres:18.6-alpine`, and start PostgreSQL with the
   new `/var/lib/postgresql` mount so Compose creates a new empty volume.
4. Restore the verified plain-SQL dump with
   `deploy/scripts/restore-postgres.sh --confirm ...`.
5. Start the remaining services and verify Flyway, transaction counts,
   portfolio totals, and provider freshness.

The exact Docker volume name includes `COMPOSE_PROJECT_NAME`; inspect it with
`docker volume ls` before renaming. Never remove the old volume until the new
18.6 database and the application have passed the restore checks.

## Important state transitions

### Market data

Adding a tracked instrument resolves and confirms the ETF identity. A sync
request fetches at least five years of daily data when no bar exists;
subsequent syncs request only the range after the last successful stored bar.
The instrument retains `UNAVAILABLE` or `INSUFFICIENT_HISTORY` when its first
sync cannot produce usable bars, so the UI can expose the incomplete state.
When enabled, the weekday scheduler runs at 18:30 `America/New_York`, upserts
missing daily bars and split events, then rebuilds the current portfolio daily
snapshot. Metrics are calculated from the stored bars when requested; the
scheduler does not fetch profile/NAV data. Provider changes saved through
Settings are read by subsequent market-data requests; the scheduler's
trigger and all business-date decisions remain fixed to New York exchange time.

### Transactions

BUY and SELL require a date, instrument, quantity, and unit price. DIVIDEND and
standalone FEE use an explicit amount. Fractional shares are supported. The current
API stores transaction currency as USD and does not accept a currency request
field. A transaction may point to a plan cycle, but the association is
nullable so planned and unplanned activity coexist.

BUY transactions also carry an explicit contribution source. A cycle-linked
BUY is `DCA`; an opening-date BUY can be `INITIAL` for a selected plan; an
explicit `UNPLANNED` BUY belongs to no plan. Legacy rows may remain
unclassified. The service never infers initial capital from date alone, because
that would rewrite user intent. SELL, DIVIDEND, and FEE cannot carry these
fields.

Historical transaction edits are not a simple row update: the service replays
from the earliest affected date, validates that no later SELL creates negative
shares, and rebuilds lots, cycles, and snapshots. If the replay is invalid,
the entire mutation is rejected.

### Plan cycles

For the current monthly UI, a cycle is represented by `YYYY-MM`. Its planned amount
and target assets are frozen when created. During the execution window, status
is `OPEN` with no execution, `PARTIAL` after a positive partial BUY, and
`COMPLETED` when the executed amount reaches the planned amount. Before the
window it is `UPCOMING`; after a zero-execution window it is `SKIPPED`.

When a plan month contains an actual classified `INITIAL` BUY and no linked DCA
execution, its DCA projection is a zero-budget `SKIPPED` cycle. The API rejects
a DCA transaction for that month. A start month without actual initial capital
remains an ordinary DCA cycle.

### Recommendation

The recommendation service reads current projected holdings and the active
plan. It calculates target values after the proposed contribution, excludes
overweight assets, and distributes the contribution across positive gaps. It
does not sell assets and does not include unplanned ETFs in the plan's target
allocation calculation.

## Security and operations

The current production profile is single-user. `APP_USERNAME` and a precomputed
BCrypt `APP_PASSWORD_HASH` are supplied through the deployment environment; the
current `BCryptPasswordEncoder` does not accept Argon2id or delegating `{id}`
formats. The API
uses an HttpOnly, Secure, SameSite=Lax session cookie, rotates the session ID
on successful login, stores the session in PostgreSQL through Spring Session,
and enforces CSRF protection. Sessions therefore normally survive an API
container restart while logout deletes the stored session. JWT,
registration, and password reset are outside the current product scope.

Provider keys and database credentials are read only by Compose/API. They do
not appear in React environment variables, API responses, logs, or Git. The
tracked `deploy/.env.example` contains placeholders only. The real
`deploy/.env` and generated `deploy/backups/` directory are ignored.

Spring Actuator provides the internal health check consumed by Compose. The
public API health endpoint must return a minimal status and must not expose
credentials, SQL details, or provider keys. Caddy terminates HTTPS with
Let's Encrypt and forwards the original host/protocol headers to the API.

The host-side backup script creates compressed plain SQL dumps, keeps seven
daily and four weekly files, and sets restrictive permissions. The restore
script requires `--confirm` and creates a safety backup first by default. See
the operational commands in the root README.

## Failure behavior

Provider failures are represented as `STALE`, `PARTIAL`, or `UNAVAILABLE` data
states. Existing prices remain visible with their `asOf`/`retrievedAt` times
when possible. A failure in both market-data providers must not turn the
dashboard into HTTP 500. Missing prices disable calculations that cannot be
made honestly; they are never filled with a current price, a market price
copied into NAV, or a fabricated zero return.

The API emits structured JSON logs with provider, symbol, source, row count,
duration, and error category. Secrets and full credentials are excluded.
