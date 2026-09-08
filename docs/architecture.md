# DCA Terminal Architecture

> Current baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`.
> Current schema chain: `V001`–`V022`.

## Product boundary

DCA Terminal is a single-user, USD-first ETF DCA execution and portfolio-observation terminal. It records account facts, derives holdings/cash/performance, and helps the user execute a monthly investment plan. It does not place orders, connect to a broker, provide investment advice, or create an editable holdings/cash state.

Current non-goals include broker APIs, automatic trading, options, cryptocurrency, individual-stock research, technical indicators, news, social features, paper trading, tax calculation, backtesting, and multi-user SaaS.

## Runtime shape

```text
Internet
   |
   v
Caddy (:80/:443)
   |-- /       -> web:80
   `-- /api/*  -> api:8080 -> postgres:5432
```

Repository:

```text
apps/web    React 19 + TypeScript + Vite
apps/api    Spring Boot 3.5.16 + Java 21
deploy      Compose, Caddy, backup/restore/smoke
e2e         isolated Playwright stack
docs        current contracts + dated historical evidence
```

PostgreSQL is on an internal Compose network. Only Caddy publishes host ports.

## Authoritative facts

The authoritative business facts are:

- instrument identity/profile;
- market quote and daily market price;
- fund NAV;
- split event;
- transaction ledger event;
- contribution attribution attached to eligible funding/BUY rows;
- investment plan and plan assets.

The account is a projection:

```text
ordered transaction ledger
      + split events
      + market data
          |
          +--> security FIFO replay
          +--> cash replay
          |
          v
cash-inclusive portfolio
          |
          +--> summary / holdings / allocation
          +--> regular-close snapshots/history
          +--> performance engine
          `--> plan/contribution projections
```

There is no holdings-update endpoint and no mutable cash-balance table. `portfolio_snapshot_daily` is a rebuildable cache, not a second source of truth.

## Transaction and cash model

Current transaction types:

- `DEPOSIT`
- `WITHDRAWAL`
- `INTEREST`
- `BUY`
- `SELL`
- `DIVIDEND`
- `FEE`

Instrument rules:

- BUY/SELL/DIVIDEND require an instrument;
- FEE may optionally carry an instrument;
- DEPOSIT/WITHDRAWAL/INTEREST are account-level and must not carry an instrument.

Cash replay:

```text
DEPOSIT      +amount
WITHDRAWAL   -amount
BUY          -(quantity * unitPrice + fee)
SELL         +(quantity * unitPrice - fee)
DIVIDEND     +amount
FEE          -amount
INTEREST     +amount
```

External performance flow is intentionally narrower:

```text
external flow = DEPOSIT - WITHDRAWAL
```

BUY/SELL/DIVIDEND/FEE/INTEREST are internal account activity and contribute zero external performance flow.

## Portfolio model

`PortfolioService` replays both securities and cash for the same `asOf` date.

```text
securitiesValue = sum(position shares * selected price)
cashBalance     = cash replay balance
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative external flow
```

`marketValue` is a legacy field name that now means total account value.

Current holdings and security allocation remain security-only concepts. Cash is shown separately in portfolio summary/presentation and is not represented as a pseudo-instrument.

For a complete current valuation:

```text
totalPnl = marketValue - netInvested
```

Security unrealized P/L remains `securitiesValue - security costBasis`.

## Price paths

Three price paths remain deliberately separate:

1. **Latest quote** — persisted in `market_quote_latest`; current account valuation prefers the newest valid regular/pre/post/extended/overnight observation.
2. **1D intraday** — on-demand provider bars; not persisted as permanent five-minute history.
3. **Daily history** — persisted regular-session rows in `market_price_daily`; used for historical replay, snapshots, ETF metrics, and regular-close performance.

Market price, adjusted close, and NAV are separate facts. NAV is never synthesized from market price.

## Performance module

The backend `performance` module is the canonical portfolio-performance boundary.

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

It consumes:

- regular-close portfolio history;
- current cash-inclusive summary;
- external cash flows from DEPOSIT/WITHDRAWAL only.

A complete `FRESH` current valuation can extend the regular-close history with a live endpoint. PARTIAL live valuations are rejected as performance endpoints to prevent missing quotes from appearing as investment losses.

The response exposes TWR, CAGR, XIRR, maximum drawdown, baseline/inception/endpoint metadata, an `externalFlowModel`, and normalized performance points.

Benchmark search/history is a separate read-only module. Benchmark ETF/index/equity identities do not enter the tracked instrument table or portfolio ledger.

## Today semantics

The New York calendar defines the account business day, but Today performance is anchored to the previous completed regular close.

`V020` introduced an experimental midnight-settlement table. `V021` removed that runtime model. These migrations remain in forward-only Flyway history; do not delete or rewrite them.

## Contribution and plan model

Plan cycles freeze historical intent. Later edits to a plan must not rewrite a frozen cycle.

Actual DCA execution is a BUY linked to a cycle. DEPOSIT only funds cash; it is not an executed purchase.

Contribution analysis remains BUY-lot attribution:

- `INITIAL` BUY linked to the requested plan;
- cycle-linked `DCA` BUY grouped by cycle period;
- `UNPLANNED` or unclassified BUY excluded from plan batch totals;
- SELL globally consumes split-adjusted FIFO lots and can realize attributed batch P/L.

DEPOSIT rows may carry contribution attribution in the database/API, including plan attribution for DCA funding, but the current batch-analysis projection does not convert deposit funding into contribution lots.

## Modules

Current API module ownership:

- `security` / `auth` — single-user session security, CSRF, login throttling;
- `instrument` — tracked ETF identity/profile;
- `marketdata` — provider SPI, quotes, history, intraday, sync/freshness;
- `benchmark` — read-only Yahoo-searchable ETF/index/equity comparisons;
- `transaction` — CRUD, cash ledger, FIFO validation boundary, CSV import;
- `portfolio` — current projection, historical replay, snapshots;
- `performance` — cash-flow-neutral performance engine;
- `plan` — plans, cycles, recommendations, contribution analysis/classification;
- `settings` — non-secret user settings/provider configuration state;
- `observability` — low-cardinality metrics.

## Persistent schema

Flyway is the only schema owner. Production uses Hibernate `ddl-auto=validate`.

Current published migrations:

- `V001`–`V012`: initial instrument/market/transaction/plan/snapshot model and early corrections;
- `V013`: atomic ledger-order sequence;
- `V014`: PostgreSQL Spring Session;
- `V015`: remove configurable timezone setting;
- `V016`: contribution fields;
- `V017`: contribution constraints/backfill/audit;
- `V018`: quote-session classification;
- `V019`: remove untrusted portfolio snapshots after corrected replay semantics;
- `V020`: create experimental midnight settlement;
- `V021`: remove midnight settlement;
- `V022`: explicit cash ledger, cash transaction types, cash-inclusive snapshot columns, compatibility bridge rows.

Important logical tables now include:

- `instrument`
- `market_price_daily`
- `market_quote_latest`
- `fund_nav_daily`
- `instrument_split`
- `investment_transaction`
- `investment_plan`
- `investment_plan_asset`
- `investment_plan_cycle`
- `investment_plan_cycle_asset`
- `portfolio_snapshot_daily`
- `app_setting`
- `SPRING_SESSION`
- `SPRING_SESSION_ATTRIBUTES`
- `contribution_classification_audit`

`portfolio_snapshot_daily.market_value` now means total account value. `securities_value` and `cash_balance` were added in V022. `net_cash_flow` represents cumulative external flow.

## Decimal and time rules

Financial values use `BigDecimal`/`NUMERIC`, not Java `double` or floating-point database columns. API `BigDecimal` responses are serialized as plain decimal JSON strings; frontend calculations use `decimal.js-light`.

The isolated fractional-power boundaries required for CAGR may use `Math.pow` after validating positive finite values; this is the documented exception, not a general financial-number policy.

Database timestamps are UTC. Business dates and US plan/market decisions use `America/New_York`.

## Migration compatibility

V022 makes an explicit semantic migration for legacy accounts. Existing pre-V022 BUY/SELL activity had implicitly injected/removed cash. To preserve old economic behavior, V022 inserts deterministic bridge DEPOSIT/WITHDRAWAL rows around legacy trades and invalidates old snapshots.

Do not remove bridge rows or edit V022 after publication. Restoring a pre-V022 dump into a V022 application must proceed through Flyway and then be validated against cash balance, total account value, and performance controls.

## Deployment invariants

- PostgreSQL 18+ volume is mounted at `/var/lib/postgresql`.
- Major-version database upgrades use logical dump/restore.
- Never use `docker compose down -v` as a normal upgrade action.
- Provider keys stay server-side.
- Live mode never falls back to demo fixtures after an API failure.
- Demo mode is explicitly built and visibly labeled.

## Documentation priority

When sources conflict, use this order:

```text
current source + published migration
> current tests
> current runtime probe / CI evidence
> current living docs
> dated sa-*.md evidence
```

Historical `docs/sa-*.md` files are intentionally not rewritten as current-state documentation.
