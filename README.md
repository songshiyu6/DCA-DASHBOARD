# DCA Terminal

> Current documentation baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e` (PR #42, 2026-09-04).
> Current Flyway chain: `V001` through `V022`.

DCA Terminal is a single-user, USD-first ETF investing and DCA execution dashboard. It is designed to answer four questions with auditable data:

1. What actually happened in the account?
2. What does the account own and how much cash is available now?
3. Is the monthly DCA plan being executed as intended?
4. What investment performance remains after external funding and withdrawals are removed?

The project is not a broker, order-entry system, trading terminal, research product, or financial-advice service. It deliberately excludes broker APIs, automatic order placement, options, cryptocurrency, individual-stock research, technical indicators, price prediction, news, social features, paper trading, tax calculation, and multi-user SaaS.

## Current product

The authenticated Web application exposes these workspaces:

- **Dashboard** — cash-inclusive account value, security holdings, Today P/L, plan progress, allocation, and investment-performance chart.
- **Plan** — one active monthly USD plan, frozen monthly cycles, execution windows, target weights, progress, and contribution-first recommendations.
- **Contributions** — plan-scoped `INITIAL`/`DCA` BUY-lot analysis, unclassified legacy BUY queue, classification preview/commit, and audit history.
- **ETFs** — tracked ETF identity, quote, NAV when available, daily/intraday history, metrics, sync, and provider freshness.
- **Transactions** — `DEPOSIT`, `WITHDRAWAL`, `INTEREST`, `BUY`, `SELL`, `DIVIDEND`, and `FEE`, plus server-authoritative CSV preview/commit.
- **Settings** — theme and market-data provider selection/configuration status.

The performance panel supports `1M`, `3M`, `1Y`, `YTD`, and `ALL`, and can overlay arbitrary Yahoo-searchable ETF, index, or equity benchmarks without adding those benchmarks to the tracked-instrument/portfolio domain.

## Source of truth

The transaction ledger is the source of truth for account activity. There is no editable holdings state and no editable cash-balance state.

```text
transactions + splits + market data
             |
             +--> security FIFO lots / holdings
             +--> cash ledger
             |
             v
      cash-inclusive portfolio value
             |
             +--> dashboard / allocation / snapshots
             +--> TWR / CAGR / XIRR / drawdown
             `--> plan/contribution projections
```

Daily portfolio snapshots are rebuildable caches. They may be invalidated after backdated transaction, split, or historical-price changes and must never become a second fact source.

### Cash ledger

Cash is replayed from the same ordered transaction ledger:

| Event | Cash effect | External performance flow? |
| --- | ---: | --- |
| `DEPOSIT` | `+amount` | yes |
| `WITHDRAWAL` | `-amount` | yes |
| `BUY` | `-(quantity * price + fee)` | no |
| `SELL` | `+(quantity * price - fee)` | no |
| `DIVIDEND` | `+amount` | no |
| `FEE` | `-amount` | no |
| `INTEREST` | `+amount` | no |

Only `DEPOSIT` and `WITHDRAWAL` are external capital flows. Buying or selling a security moves value between cash and securities inside the account and therefore must not create or remove investment performance.

The current summary contract is:

```text
securitiesValue = sum(open shares * current security price)
cashBalance     = replay(all cash effects)
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative DEPOSIT - WITHDRAWAL
totalPnl        = marketValue - netInvested        # when valuation is complete
```

`marketValue` is therefore total account value, despite the historical field name.

### Legacy cash migration

`V022__introduce_cash_ledger.sql` preserves the economic meaning of pre-cash-ledger accounts by inserting deterministic bridge cash events around legacy BUY/SELL rows:

- a legacy BUY receives a preceding synthetic `DEPOSIT` for its cash outlay;
- a legacy SELL receives a following synthetic `WITHDRAWAL` for its net proceeds;
- unusual negative SELL proceeds are bridged defensively;
- existing snapshots are invalidated because pre-V022 history did not contain explicit cash.

These rows are migration facts used to preserve compatibility. Do not delete them just because they are system-generated.

## Performance semantics

Portfolio performance is now calculated by the backend `performance` module and exposed at:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

The engine uses:

- regular-close cash-inclusive portfolio valuations for historical points;
- at most one current live endpoint;
- only a complete `FRESH` live valuation, so a missing quote cannot manufacture a loss;
- `DEPOSIT - WITHDRAWAL` as the external-flow stream;
- TWR for capital-flow-neutral performance;
- XIRR for money-weighted personal return;
- CAGR and maximum drawdown from the performance level series.

The dashboard may retain local calculation code as a compatibility fallback, but the server performance endpoint is the canonical live contract.

Today performance is anchored to the previous completed regular close, not midnight and not the newest same-day stored close. `V020` created an experimental midnight-settlement table; `V021` removed it while preserving forward-only Flyway history.

## Contribution semantics

Plan execution and contribution-batch analysis are still based on actual BUY transactions:

- a cycle-linked BUY is `DCA`;
- an `INITIAL` BUY is linked to a plan and must occur on the plan start date;
- `UNPLANNED` BUYs remain outside plan contribution totals;
- legacy unclassified BUYs can be previewed and committed through the classification workflow;
- SELL uses the same global split-aware FIFO lots and can realize P/L from an attributed batch.

`DEPOSIT` is funding, not DCA execution. The backend can store `INITIAL`/`DCA`/`UNPLANNED` funding attribution on DEPOSIT rows, but the current Contributions projection remains BUY-lot based. Do not silently reinterpret a deposit as an executed purchase.

## Market-data semantics

Market price, adjusted close, and fund NAV are different facts and remain separate.

- **Current valuation** prefers the newest valid timestamped regular/pre-market/post-market/extended/overnight quote.
- **Historical portfolio valuation** uses regular-session raw closes and historical ledger replay.
- **ETF return metrics** use adjusted close where documented.
- **NAV** is stored separately and is never replaced with market price.
- **1D chart** uses on-demand provider bars and is not persisted as a five-minute database.
- Provider errors, a valid pre-open empty response, a closed market, and a post-open data anomaly are distinct states.

Benchmark history is isolated from tracked instruments and portfolio facts. ETF/index/equity benchmark selection is read-only and browser-persisted; it does not create an instrument, holding, or transaction.

## Repository layout

```text
.
├── apps/
│   ├── web/                 # React 19 + TypeScript + Vite
│   └── api/                 # Spring Boot 3.5.16 + Java 21
├── deploy/
│   ├── docker-compose.yml   # web, api, postgres, caddy
│   ├── docker-compose.e2e.yml
│   ├── Caddyfile
│   ├── .env.example
│   └── scripts/             # backup, restore, deployment smoke
├── e2e/                     # Playwright against isolated mock-provider stack
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── calculations.md
│   ├── market-data.md
│   ├── operations-runbook.md
│   ├── agent-handoff.md
│   └── next-development-plan.md
└── .github/workflows/ci.yml
```

The API is a modular monolith. Current modules include `benchmark`, `instrument`, `marketdata`, `transaction`, `portfolio`, `performance`, `plan`, `settings`, `security`, and `observability`.

## Runtime and configuration

Expected local toolchain:

- Node.js 22 for `apps/web`;
- Java 21 for `apps/api`;
- Docker Engine + Docker Compose v2 for the full stack.

Production Compose defaults to PostgreSQL `18.6-alpine`. PostgreSQL 18+ uses the parent `/var/lib/postgresql` mount with a versioned data directory beneath it. A data directory from PostgreSQL 16 or another major version must be upgraded with logical dump/restore; changing only the image tag is not an upgrade.

Create the untracked deployment environment file:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set real values for at least `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, `APP_USERNAME`, and `APP_PASSWORD_HASH`. The application currently uses Spring BCrypt password encoding; do not commit plaintext passwords, password hashes, provider keys, cookies, sessions, or database credentials.

`TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` are optional server-side provider credentials. `YAHOO_PROXY_URL` is also server-side. Provider credentials must never become Vite variables or browser-visible API fields.

Business dates, plan windows, US market-session boundaries, and portfolio day rollover use `America/New_York`. Database timestamps remain UTC. There is no user-configurable business timezone.

## Development

Web:

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm test -- --run
npm run build
```

API:

```bash
cd apps/api
./gradlew test build --no-daemon
./gradlew postgresTest --no-daemon
```

Deployment graph:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

Isolated E2E:

```bash
DCA_E2E_SUITE=smoke bash e2e/run.sh
# or explicitly request the full suite
DCA_E2E_SUITE=full bash e2e/run.sh
```

Do not run E2E against a production volume or production `.env`.

## CI

`.github/workflows/ci.yml` currently checks:

- Web install, production dependency audit, lint, typecheck, unit tests, and build;
- API tests/build on Java 21;
- PostgreSQL 18.6 Flyway + Hibernate validation through `postgresTest`;
- Compose and shell-script validation;
- PostgreSQL backup/restore smoke;
- temporary Caddy/API deployment smoke;
- isolated Playwright E2E;
- repository whitespace hygiene.

The npm production-audit gate fails on actual vulnerability findings but treats recognized npm-registry/audit-service outages as a warning rather than misreporting them as dependency success or failure.

A missing GitHub status response is not evidence that CI passed. Release claims must reference a concrete current workflow run or equivalent verified evidence.

## Flyway state

Current published chain: `V001`–`V022`.

Key recent migrations:

| Migration | Purpose |
| --- | --- |
| `V013` | atomic transaction ledger-order sequence |
| `V014` | PostgreSQL-backed HTTP sessions |
| `V015` | remove obsolete timezone setting |
| `V016` | contribution tracking fields |
| `V017` | contribution-source constraints, legacy backfill, classification audit |
| `V018` | latest-quote session classification |
| `V019` | remove snapshots that could not be trusted under corrected replay semantics |
| `V020` | create experimental midnight settlement table |
| `V021` | remove midnight settlement; restore previous-regular-close daily semantics |
| `V022` | explicit cash ledger, new cash transaction types, cash-inclusive snapshots/performance |

Migrations are forward-only. Never edit an already published migration to make a later application version look compatible.

## Deployment and backups

Production topology:

```text
Internet -> Caddy (80/443) -> web
                         `-> /api/* -> Spring Boot -> PostgreSQL
```

Only Caddy publishes host ports. PostgreSQL stays on the internal network.

Before an upgrade that can change schema or financial projections:

```bash
deploy/scripts/backup-postgres.sh
gzip -t deploy/backups/daily/<backup>.sql.gz
```

Then rebuild the stack without deleting volumes:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

Never use `docker compose down -v` as a normal upgrade step.

See `docs/operations-runbook.md` for backup, restore, PostgreSQL-major-upgrade, session, and smoke procedures.

## Current known gaps

The current code is functional, but the next development plan still includes important cleanup and product work:

- live transaction, plan, and CSV forms still contain fixed sample/default facts that should be removed;
- the UI needs a concentrated DCA action queue for open/partial/missed cycles;
- cash-funding attribution and BUY-lot contribution analysis need clearer user-facing reconciliation;
- provider health history and market-data gap audit are not yet first-class operator views;
- full account export/recovery evidence is incomplete;
- transaction list/history paths still need capacity-oriented pagination/range work;
- some contribution/transaction copy remains outside the i18n catalog.

The authoritative prioritized list is `docs/next-development-plan.md`.

## Documentation map

- [Architecture](docs/architecture.md) — boundaries, modules, facts, projections, schema, runtime.
- [API](docs/api.md) — current HTTP contract.
- [Calculations](docs/calculations.md) — cash, portfolio, performance, ETF, FIFO, plan, contribution formulas.
- [Market data](docs/market-data.md) — provider, quote/history/intraday/benchmark, retry, freshness.
- [Operations runbook](docs/operations-runbook.md) — deploy, upgrade, backup, restore, smoke, rollback.
- [Agent handoff](docs/agent-handoff.md) — current implementation state and takeover rules.
- [Next development plan](docs/next-development-plan.md) — current gaps, priorities, release gates.

Files named `docs/sa-*.md` are dated historical evidence for the commits they name. They are intentionally not rewritten into current-state documents.
