# DCA Terminal

> Current Flyway chain: `V001` through `V025`.

DCA Terminal is a focused single-user investing and DCA dashboard. The real account ledger remains USD-first for US ETFs. V023–V024 added a separate CNY China-mutual-fund automatic-DCA projection; V025 adds persisted USD/CNY FX facts and a separate USD **Reporting** projection that can combine the real USD account with the derived CNY fund subportfolio without pretending the real cash ledger is already multi-currency.

The product is designed to answer four questions with auditable data:

1. What actually happened in the account?
2. What does the account own and how much cash is available now?
3. Is the DCA plan being executed as intended?
4. What investment performance remains after external funding and withdrawals are removed?

It is not a broker, order-entry system, trading terminal, research product, or financial-advice service. It deliberately excludes broker APIs, automatic order placement, options, cryptocurrency, individual-stock research, price prediction, tax calculation, and multi-user SaaS.

## Current product

Authenticated Web workspaces:

- **Dashboard** — the real USD account: cash-inclusive value, holdings, Today P/L, plan progress, allocation, and canonical USD portfolio performance.
- **Reporting** — a USD reporting projection over the real USD account plus CNY automatic-fund-DCA projections using persisted historical USD/CNY FX.
- **Plan** — one active monthly USD plan, frozen cycles, execution windows, targets, progress, and recommendations.
- **Contributions** — INITIAL/DCA BUY-lot analysis, unclassified BUY queue, classification preview/commit, and audit.
- **ETFs** — tracked US ETF identity, quote, NAV when available, history, metrics, sync, and freshness.
- **China Funds** — CNY mutual-fund metadata, NAV synchronization/gap audit, automatic-DCA rules, monthly/yearly summaries, and daily derived drilldown.
- **Transactions** — DEPOSIT/WITHDRAWAL/INTEREST/BUY/SELL/DIVIDEND/FEE plus server-authoritative CSV preview/commit.
- **Settings** — theme and market-data provider configuration state.

The real portfolio and the combined reporting projection are intentionally different surfaces. A CNY derived fund position shown in Reporting is not a real `investment_transaction`, cash balance, or broker holding.

## Source of truth

### Real USD account

The ordered transaction ledger is the source of truth for real account activity. There is no editable holdings state and no editable cash-balance state.

```text
transactions + splits + market data
             |
             +--> security FIFO lots / holdings
             +--> cash ledger
             |
             v
      real USD account value
             |
             +--> Dashboard / allocation / snapshots
             `--> /performance/portfolio
```

Cash replay:

| Event | Cash effect | External USD performance flow? |
| --- | ---: | --- |
| `DEPOSIT` | `+amount` | yes |
| `WITHDRAWAL` | `-amount` | yes |
| `BUY` | `-(quantity * price + fee)` | no |
| `SELL` | `+(quantity * price - fee)` | no |
| `DIVIDEND` | `+amount` | no |
| `FEE` | `-amount` | no |
| `INTEREST` | `+amount` | no |

```text
securitiesValue = sum(open shares * current security price)
cashBalance     = replay(all cash effects)
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative DEPOSIT - WITHDRAWAL
totalPnl        = marketValue - netInvested        # when complete
```

`marketValue` means total real USD account value despite the historical field name.

### CNY automatic-fund-DCA projection

China-fund history has a separate source chain:

```text
auto_dca_rule + fund_nav_daily + fund_market_calendar_day
                         |
                         v
                derived daily purchases
                         |
                         v
              cumulative CNY shares
```

`auto_dca_rule` does **not** create `investment_transaction` rows. An automatic-DCA execution exists only when that fund has an observed NAV for the date. A confirmed China open date without NAV is a visible data gap and never receives a carry-forward NAV to create a purchase.

Editing a rule currently has `REWRITE_HISTORY` semantics, so its derived historical reporting projection can change.

### FX facts and unified reporting

V025 stores FX in `fx_rate_daily`, separate from security prices and fund NAV. The current pair is:

```text
USD/CNY: 1 USD = rate CNY
```

The initial adapter uses Yahoo `CNY=X`. It is a reporting market rate, not an official PBOC fixing or bank conversion quote.

For CNY amount `C` and USD/CNY rate `R`:

```text
USD value = C / R
```

The reporting projection uses two distinct dates:

- each synthetic CNY DCA gross contribution is converted at the FX rate on or immediately before its **execution/flow date**;
- CNY fund market value is converted at the FX rate on or immediately before the **valuation date**.

This makes FX movement part of investment performance instead of retroactively changing historical capital contributions.

```text
combinedValueUsd
  = realUsdAccountValue
  + derivedCnyFundValue / valuationDateUsdCny

combinedExternalFlowUsd
  = realUsdDepositsMinusWithdrawals
  + sum(each CNY DCA gross amount / its flowDateUsdCny)

combinedPnlUsd
  = combinedValueUsd - combinedExternalFlowUsd
```

Combined TWR/CAGR/XIRR/max drawdown reuse the same backend `PerformanceEngine` through a different `PortfolioPerformanceSource`; there is no second performance formula implementation.

If no CNY automatic-DCA history exists, the Reporting endpoint reduces exactly to the existing USD account and requires no FX data.

Missing required FX or fund NAV is never guessed. FX carry-forward is bounded to seven calendar days. An incomplete CNY component degrades combined reporting to `PARTIAL` and prevents a fabricated live performance endpoint.

## APIs: USD account vs reporting

Canonical real USD portfolio performance remains:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Its external-flow model remains:

```text
CASH_LEDGER_DEPOSIT_WITHDRAWAL
```

Unified reporting is separate:

```text
GET  /api/v1/reporting/multicurrency?range=1M|3M|1Y|YTD|ALL
GET  /api/v1/fx/usd-cny?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
POST /api/v1/fx/usd-cny/sync?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

Its external-flow model when CNY activity exists is:

```text
USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY
```

The real `/portfolio/*`, `/dashboard`, and `/performance/portfolio` contracts are not redefined by V025.

## China mutual funds

V023 introduced `instrument_type=MUTUAL_FUND`, CNY fund metadata, and `auto_dca_rule`.

V024 added:

- persisted `fund_market_calendar_day` confirmed China open dates;
- EastMoney NAV + Shanghai-market open-date synchronization;
- `/api/v1/funds/{id}/sync` and calendar/NAV gap audit;
- weekday 22:30 `Asia/Shanghai` recent refresh;
- China Funds Web workspace;
- persisted-calendar T+n confirmation timing with NAV-date fallback only for uncovered confirmation history.

The DCA amount is gross cash paid. For purchase-fee rate `r`:

```text
net subscription = gross amount / (1 + r)
purchase fee     = gross amount - net subscription
shares           = net subscription / NAV
```

Management fee is metadata only because published fund NAV already reflects fund-level accrued expenses.

EastMoney is an unofficial source. Provider failure must preserve existing local NAV/calendar facts rather than convert failure into financial data.

## Market-data facts

Keep these facts separate:

```text
latest market quote
regular-session raw close
provider adjusted close
fund NAV
China confirmed open day
USD/CNY daily FX rate
split event
```

- current US ETF valuation may use the newest valid regular/pre/post/extended/overnight quote;
- historical real USD portfolio valuation uses regular-session raw close;
- ETF return metrics use adjusted close where documented;
- fund NAV lives in `fund_nav_daily`;
- China open dates live in `fund_market_calendar_day`;
- FX lives in `fx_rate_daily`;
- Reporting reads persisted local facts and does not make provider calls during the page read.

## Repository layout

```text
.
├── apps/
│   ├── web/                 # React 19 + TypeScript + Vite
│   └── api/                 # Spring Boot 3.5.16 + Java 21
├── deploy/
│   ├── docker-compose.yml
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
│   ├── multicurrency-phase3.md
│   ├── operations-runbook.md
│   ├── agent-handoff.md
│   └── next-development-plan.md
└── .github/workflows/ci.yml
```

The API is a modular monolith. Current modules include `benchmark`, `fund`, `fx`, `instrument`, `marketdata`, `transaction`, `portfolio`, `performance`, `reporting`, `plan`, `settings`, `security`, and `observability`.

## Runtime and configuration

Expected local toolchain:

- Node.js 22;
- Java 21;
- Docker Engine + Docker Compose v2.

Production Compose defaults to PostgreSQL `18.6-alpine`.

Create the untracked deployment environment:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set real values for `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, `APP_USERNAME`, and `APP_PASSWORD_HASH`. Never commit plaintext passwords, password hashes, provider keys, cookies, sessions, proxy credentials, or database credentials.

Important sync controls:

```text
MARKET_SYNC_ENABLED / MARKET_SYNC_CRON
FUND_SYNC_ENABLED / FUND_SYNC_CRON
FX_SYNC_ENABLED / FX_SYNC_CRON
YAHOO_PROXY_URL
TWELVE_DATA_API_KEY
ALPHA_VANTAGE_API_KEY
EASTMONEY_FUND_NAV_BASE_URL
EASTMONEY_MARKET_BASE_URL
EASTMONEY_TIMEOUT_MS
```

US business/market dates use `America/New_York`. China fund provider/calendar interpretation uses `Asia/Shanghai`. Database timestamps remain UTC.

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

Do not run E2E against production volumes or production `.env`.

## CI

`.github/workflows/ci.yml` checks:

- Web install, production dependency audit, lint, typecheck, unit tests, build;
- API tests/build on Java 21;
- PostgreSQL 18.6 Flyway + Hibernate validation;
- Compose/shell validation;
- PostgreSQL backup/restore smoke;
- temporary Caddy/API deployment smoke;
- isolated Playwright E2E;
- repository whitespace hygiene.

A missing status response is not evidence that CI passed. Release claims must reference a concrete workflow run for the exact target head.

## Flyway state

Current published chain on this feature: `V001`–`V025`.

Recent migrations:

| Migration | Purpose |
| --- | --- |
| `V017` | contribution constraints/backfill/audit |
| `V018` | quote session classification |
| `V019` | remove untrusted snapshots |
| `V020` | experimental midnight settlement |
| `V021` | remove midnight settlement |
| `V022` | explicit cash ledger and cash-inclusive USD account |
| `V023` | CNY mutual-fund + automatic-DCA projection foundation |
| `V024` | persisted China open dates and NAV gap audit |
| `V025` | persisted daily FX facts for reporting-currency conversion |

Migrations are forward-only. Never edit a published migration to make a later application version look compatible.

## Deployment and backups

Before an upgrade that changes schema or financial projections:

```bash
deploy/scripts/backup-postgres.sh
gzip -t deploy/backups/daily/<backup>.sql.gz
```

Then rebuild without deleting volumes:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

Never use `docker compose down -v` as a routine upgrade step.

## Current known gaps

Important remaining work:

- the **real** transaction/cash ledger is still USD-only; CNY manual cash/fund transactions are not yet real ledger facts;
- V025 supports USD as the reporting currency and USD/CNY as the current FX pair, not an arbitrary currency matrix;
- Yahoo `CNY=X` needs the same provider-health/gap/fallback discipline as other external sources;
- AUTO vs MANUAL China-fund investment analysis is not unified because manual real CNY transactions are not implemented;
- auto-DCA rule edits still rewrite derived history rather than versioning changes from an effective date;
- China open-day history is confirmed/persisted data, not a future official holiday calendar;
- EastMoney remains an unofficial source and needs operational monitoring/fallback work;
- live transaction/plan/CSV forms still contain submit-able sample/default facts that should be removed;
- no concentrated DCA action queue yet;
- full export/recovery evidence is incomplete;
- transaction/history paths still have capacity/pagination work;
- some copy remains outside i18n catalogs.

## Documentation map

- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Calculations](docs/calculations.md)
- [Market data](docs/market-data.md)
- [China fund auto-DCA Phase 1](docs/cn-fund-auto-dca-phase1.md)
- [China fund Phase 2](docs/cn-fund-phase2.md)
- [Multi-currency reporting Phase 3](docs/multicurrency-phase3.md)
- [Operations runbook](docs/operations-runbook.md)
- [Agent handoff](docs/agent-handoff.md)
- [Next development plan](docs/next-development-plan.md)

Files named `docs/sa-*.md` are dated historical evidence for the commits they name and are intentionally not rewritten into current-state documents.