# DCA Terminal

> Current Flyway chain: `V001` through `V024`.

DCA Terminal is a focused single-user investing and DCA dashboard. The established real-account path remains USD-first for US ETFs. V023–V024 add a separate CNY China-mutual-fund workspace with rule-driven automatic-DCA projections, persisted open-day/NAV facts, provider synchronization, and month/year analysis without numerically mixing CNY into the USD portfolio.

The product is designed to answer four questions with auditable data:

1. What actually happened in the account?
2. What does the account own and how much cash is available now?
3. Is the DCA plan being executed as intended?
4. What investment performance remains after external funding and withdrawals are removed?

The project is not a broker, order-entry system, trading terminal, research product, or financial-advice service. It deliberately excludes broker APIs, automatic order placement, options, cryptocurrency, individual-stock research, technical indicators, price prediction, news, social features, paper trading, tax calculation, and multi-user SaaS.

## Current product

The authenticated Web application exposes:

- **Dashboard** — cash-inclusive USD account value, security holdings, Today P/L, plan progress, allocation, and investment-performance chart.
- **Plan** — one active monthly USD plan, frozen monthly cycles, execution windows, target weights, progress, and contribution-first recommendations.
- **Contributions** — plan-scoped `INITIAL`/`DCA` BUY-lot analysis, unclassified legacy BUY queue, classification preview/commit, and audit history.
- **ETFs** — tracked US ETF identity, quote, NAV when available, daily/intraday history, metrics, sync, and provider freshness.
- **China Funds** — CNY mutual-fund metadata, NAV synchronization/gap audit, automatic-DCA rules, monthly/yearly summaries, and explicit daily derived drilldown.
- **Transactions** — `DEPOSIT`, `WITHDRAWAL`, `INTEREST`, `BUY`, `SELL`, `DIVIDEND`, and `FEE`, plus server-authoritative CSV preview/commit.
- **Settings** — theme and market-data provider selection/configuration status.

The performance panel supports `1M`, `3M`, `1Y`, `YTD`, and `ALL`, and can overlay arbitrary Yahoo-searchable ETF, index, or equity benchmarks without adding those benchmarks to the tracked-instrument/portfolio domain.

## Source of truth

The transaction ledger is the source of truth for real account activity. There is no editable holdings state and no editable cash-balance state.

```text
transactions + splits + market data
             |
             +--> security FIFO lots / holdings
             +--> cash ledger
             |
             v
      cash-inclusive USD portfolio value
             |
             +--> dashboard / allocation / snapshots
             +--> TWR / CAGR / XIRR / drawdown
             `--> plan/contribution projections
```

China-fund automatic DCA has a deliberately separate projection fact chain:

```text
auto_dca_rule + fund_nav_daily + fund_market_calendar_day
                         |
                         v
                derived daily purchases
                         |
                   +-----+-----+
                   |           |
                monthly      yearly
                summary      summary
```

`auto_dca_rule` does **not** create `investment_transaction` rows. Editing the rule changes its derived history on the next calculation. An automatic-DCA execution exists only when that fund has an observed NAV for the date.

`fund_market_calendar_day` stores confirmed China market-open dates. An open date without NAV is a visible data gap: it is never assigned a carry-forward NAV and never creates a synthetic purchase.

Daily portfolio snapshots are rebuildable caches. They may be invalidated after backdated transaction, split, or historical-price changes and must never become a second fact source.

### Cash ledger

Cash is replayed from the ordered real transaction ledger:

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

```text
securitiesValue = sum(open shares * current security price)
cashBalance     = replay(all cash effects)
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative DEPOSIT - WITHDRAWAL
totalPnl        = marketValue - netInvested        # when valuation is complete
```

`marketValue` is total USD account value despite the historical field name.

V024 still does **not** make the real ledger multi-currency. CNY fund projections remain outside the USD cash ledger until the later multi-currency/FX phase.

### Legacy cash migration

`V022__introduce_cash_ledger.sql` preserves pre-cash-ledger account economics by inserting deterministic bridge cash events around legacy BUY/SELL rows. These are migration facts and must not be deleted merely because they were system-generated.

## China mutual funds and automatic DCA

V023 introduced `instrument_type=MUTUAL_FUND`, CNY instruments, `fund_profile`, and `auto_dca_rule`.

V024 makes that foundation operational by adding:

- persisted `fund_market_calendar_day` confirmed open dates;
- EastMoney fund-NAV and Shanghai-market open-date synchronization through a dedicated fund adapter;
- explicit `/api/v1/funds/{id}/sync` plus per-fund calendar/NAV gap audit;
- weekday 22:30 `Asia/Shanghai` recent-window refresh;
- a China Funds Web workspace for fund/rule management, sync state, gap visibility, monthly/yearly aggregation, and on-demand daily drilldown;
- persisted-calendar T+n confirmation timing with explicit NAV-date fallback for uncovered historical confirmation ranges.

EastMoney is an unofficial external data source. Provider failure is not converted into financial facts: existing local NAV/calendar data is preserved and the sync reports failure.

The configured DCA amount is total cash paid. For purchase-fee rate `r`:

```text
net subscription = gross amount / (1 + r)
purchase fee     = gross amount - net subscription
shares           = net subscription / NAV
```

NAV date and confirmation date are separate. A persisted open-day calendar drives T+n confirmation when available; confirmation timing never changes the purchase NAV date and never manufactures a missing NAV.

Management fee is metadata only. Published mutual-fund NAV already reflects fund-level accrued expenses, so the application must not deduct annual management fees from NAV again.

Projection analysis defaults to monthly aggregation, supports yearly aggregation, and returns daily rows only when explicitly requested with `includeDaily=true`.

See:

- [`docs/cn-fund-auto-dca-phase1.md`](docs/cn-fund-auto-dca-phase1.md) for the V023 projection foundation;
- [`docs/cn-fund-phase2.md`](docs/cn-fund-phase2.md) for V024 provider, calendar, sync, and UI semantics.

## Performance semantics

Canonical portfolio performance:

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

Today performance is anchored to the previous completed regular close, not midnight and not the newest same-day stored close.

CNY mutual-fund projections are not yet included in this engine. Historical FX and a multi-currency account model are required before USD and CNY can be combined honestly.

## Contribution semantics

Existing plan execution and contribution-batch analysis remain based on actual BUY transactions:

- a cycle-linked BUY is `DCA`;
- an `INITIAL` BUY is linked to a plan and must occur on the plan start date;
- `UNPLANNED` BUYs remain outside plan contribution totals;
- legacy unclassified BUYs can be previewed and committed through the classification workflow;
- SELL uses the same global split-aware FIFO lots and can realize P/L from an attributed batch.

`DEPOSIT` is funding, not DCA execution. Do not reinterpret a deposit as an executed purchase.

China-fund synthetic auto-DCA remains separate from real BUY-lot contribution analysis until multi-currency real transactions and combined AUTO/MANUAL fund analysis are implemented.

## Market-data semantics

Market price, adjusted close, and fund NAV are different facts and remain separate.

- **Current US ETF valuation** prefers the newest valid timestamped regular/pre-market/post-market/extended/overnight quote.
- **Historical US portfolio valuation** uses regular-session raw closes and historical ledger replay.
- **ETF return metrics** use adjusted close where documented.
- **Fund NAV** is stored in `fund_nav_daily`, separately from market price.
- **China fund open dates** are stored in `fund_market_calendar_day`, separately from NAV.
- **1D ETF chart** uses on-demand provider bars and is not persisted as a five-minute database.
- Provider errors, valid empty responses, closed markets, and data anomalies remain distinct states.

China mutual funds are kept out of the legacy tracked-US-ETF feed so the ETF scheduler/provider path cannot try to synchronize them as US securities.

Benchmark history is isolated from tracked instruments and portfolio facts. ETF/index/equity benchmark selection is read-only and browser-persisted.

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
│   └── scripts/
├── e2e/
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── calculations.md
│   ├── market-data.md
│   ├── cn-fund-auto-dca-phase1.md
│   ├── cn-fund-phase2.md
│   ├── operations-runbook.md
│   ├── agent-handoff.md
│   └── next-development-plan.md
└── .github/workflows/ci.yml
```

The API is a modular monolith. Current modules include `benchmark`, `fund`, `instrument`, `marketdata`, `transaction`, `portfolio`, `performance`, `plan`, `settings`, `security`, and `observability`.

## Runtime and configuration

Expected local toolchain:

- Node.js 22 for `apps/web`;
- Java 21 for `apps/api`;
- Docker Engine + Docker Compose v2 for the full stack.

Production Compose defaults to PostgreSQL `18.6-alpine`. PostgreSQL major-version data directories must be upgraded through the documented logical dump/restore procedure.

Create the untracked deployment environment file:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set real values for at least `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, `APP_USERNAME`, and `APP_PASSWORD_HASH`. Never commit plaintext passwords, password hashes, provider keys, cookies, sessions, or database credentials.

US business dates, plan windows, market-session boundaries, and portfolio rollover use `America/New_York`. Database timestamps remain UTC. China-fund provider refresh and calendar interpretation use `Asia/Shanghai`.

Fund sync controls include:

```text
FUND_SYNC_ENABLED
FUND_SYNC_CRON
EASTMONEY_FUND_NAV_BASE_URL
EASTMONEY_MARKET_BASE_URL
EASTMONEY_TIMEOUT_MS
```

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
DCA_E2E_SUITE=full bash e2e/run.sh
```

Do not run E2E against a production volume or production `.env`.

## CI

`.github/workflows/ci.yml` checks:

- Web install, production dependency audit, lint, typecheck, unit tests, and build;
- API tests/build on Java 21;
- PostgreSQL 18.6 Flyway + Hibernate validation through `postgresTest`;
- Compose and shell-script validation;
- PostgreSQL backup/restore smoke;
- temporary Caddy/API deployment smoke;
- isolated Playwright E2E;
- repository whitespace hygiene.

A missing GitHub status response is not evidence that CI passed. Release claims must reference a concrete current workflow run or equivalent verified evidence.

## Flyway state

Current published chain on this feature: `V001`–`V024`.

Key recent migrations:

| Migration | Purpose |
| --- | --- |
| `V013` | atomic transaction ledger-order sequence |
| `V014` | PostgreSQL-backed HTTP sessions |
| `V015` | remove obsolete timezone setting |
| `V016` | contribution tracking fields |
| `V017` | contribution-source constraints, legacy backfill, classification audit |
| `V018` | latest-quote session classification |
| `V019` | invalidate snapshots that could not be trusted under corrected replay semantics |
| `V020` | create experimental midnight settlement table |
| `V021` | remove midnight settlement; restore previous-regular-close daily semantics |
| `V022` | explicit cash ledger, cash transaction types, cash-inclusive snapshots/performance |
| `V023` | CNY mutual-fund profile and rule-driven automatic-DCA projection foundation |
| `V024` | persisted China market-open dates for fund confirmation and NAV gap audit |

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

Then rebuild without deleting volumes:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

Never use `docker compose down -v` as a normal upgrade step.

See `docs/operations-runbook.md` for backup, restore, PostgreSQL-major-upgrade, session, and smoke procedures.

## Current known gaps

Important work remains:

- live/historical FX and USD reporting conversion are not implemented yet;
- the real transaction/cash ledger is still USD-only and must not numerically mix CNY;
- AUTO vs MANUAL fund-investment analysis is not yet unified;
- rule versioning for “change from this date forward” is not yet implemented; current `PUT` explicitly rewrites derived history;
- the China calendar records confirmed historical open days but does not yet provide a future official holiday schedule;
- EastMoney is an unofficial fund-data provider and needs the same operational monitoring/fallback discipline as other external providers;
- live transaction, plan, and CSV forms still contain fixed sample/default facts that should be removed;
- the UI needs a concentrated DCA action queue for open/partial/missed cycles;
- provider health history and broader market-data gap audit are not yet first-class operator views;
- full account export/recovery evidence is incomplete;
- transaction list/history paths still need capacity-oriented pagination/range work;
- some contribution/transaction copy remains outside the i18n catalog.

## Documentation map

- [Architecture](docs/architecture.md) — boundaries, modules, facts, projections, schema, runtime.
- [API](docs/api.md) — established HTTP contract.
- [Calculations](docs/calculations.md) — cash, portfolio, performance, ETF, FIFO, plan, contribution formulas.
- [Market data](docs/market-data.md) — provider, quote/history/intraday/benchmark, retry, freshness.
- [China fund auto-DCA Phase 1](docs/cn-fund-auto-dca-phase1.md) — V023 CNY fund/NAV/rule projection foundation.
- [China fund Phase 2](docs/cn-fund-phase2.md) — V024 provider sync, open-day gap audit, confirmation timing, and UI boundaries.
- [Operations runbook](docs/operations-runbook.md) — deploy, upgrade, backup, restore, smoke, rollback.
- [Agent handoff](docs/agent-handoff.md) — established implementation state and takeover rules.
- [Next development plan](docs/next-development-plan.md) — longer-term gaps and priorities; verify dated statements against current source.

Files named `docs/sa-*.md` are dated historical evidence for the commits they name and are intentionally not rewritten into current-state documents.