# DCA Terminal Architecture

> Current schema chain: `V001`–`V025`.

## Product boundary

DCA Terminal is a single-user investing/DCA terminal. The authoritative real account remains a USD cash-and-security ledger. CNY China-mutual-fund automatic-DCA history is a separate synthetic projection. V025 adds a USD reporting layer that converts that CNY projection with persisted historical USD/CNY facts and combines it with the real USD account.

The product does not place orders, connect to a broker, provide investment advice, or expose editable holdings/cash balances.

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

PostgreSQL is internal to Compose. Only Caddy publishes host ports.

## Fact and projection hierarchy

### Authoritative facts

- instrument identity/profile;
- latest market quote and daily market price;
- fund NAV;
- confirmed China market-open date;
- daily FX rate;
- split event;
- ordered real transaction ledger event;
- contribution attribution attached to eligible real funding/BUY rows;
- investment plan and frozen plan-cycle intent;
- China fund profile and automatic-DCA rule.

### Real USD account projection

```text
ordered real transaction ledger
      + split events
      + US market data
          |
          +--> split-aware security FIFO
          +--> USD cash replay
          |
          v
       real USD account
          |
          +--> summary / holdings / allocation
          +--> regular-close snapshots/history
          +--> Dashboard
          `--> canonical USD PerformanceEngine
```

There is no holdings-update endpoint and no mutable cash-balance table. `portfolio_snapshot_daily` is a rebuildable cache.

### CNY automatic-fund-DCA projection

```text
auto_dca_rule
+ fund_nav_daily
+ fund_market_calendar_day
       |
       v
derived daily purchases
       |
       v
cumulative CNY shares/value
```

These derived purchases are not `investment_transaction` rows. Rule edits currently recompute historical derived purchases.

### V025 unified reporting projection

```text
real USD account --------------------+
                                      |
derived CNY fund value -- USD/CNY -->+--> USD Reporting workspace
                                      |
CNY DCA gross flows ---- flow FX ----+--> combined PerformanceEngine source
```

The reporting layer is a projection, not a second ledger. Existing `/portfolio/*`, `/dashboard`, and `/performance/portfolio` remain real USD-account contracts.

## Real transaction and cash model

Real transaction types:

- `DEPOSIT`
- `WITHDRAWAL`
- `INTEREST`
- `BUY`
- `SELL`
- `DIVIDEND`
- `FEE`

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

Real USD external performance flow:

```text
external flow = DEPOSIT - WITHDRAWAL
```

BUY/SELL/DIVIDEND/FEE/INTEREST are internal real-account activity.

```text
securitiesValue = sum(position shares * selected price)
cashBalance     = cash replay balance
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative DEPOSIT - WITHDRAWAL
totalPnl        = marketValue - netInvested        # when complete
```

The transaction service still enforces USD real transactions in V025 even though the schema has a currency column. Do not infer that CNY cash/manual transactions are supported.

## Market fact boundaries

The following remain distinct:

1. latest traded quote;
2. regular-session raw daily close;
3. provider adjusted close;
4. fund unit NAV;
5. confirmed China open day;
6. USD/CNY daily FX rate;
7. split event.

V025 `fx_rate_daily` uses:

```text
base=USD
quote=CNY
rate semantics: 1 USD = rate CNY
```

The initial adapter is Yahoo `CNY=X`. Reporting reads local persisted FX facts and never performs provider I/O during the reporting read.

## Performance boundaries

### Real USD performance

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Consumes real cash-inclusive USD account history/current value and real DEPOSIT/WITHDRAWAL flows.

External-flow model:

```text
CASH_LEDGER_DEPOSIT_WITHDRAWAL
```

### Unified USD reporting performance

```text
GET /api/v1/reporting/multicurrency?range=1M|3M|1Y|YTD|ALL
```

Consumes:

- real USD account valuations/flows;
- derived CNY fund shares and NAV;
- persisted historical USD/CNY.

Each CNY auto-DCA gross contribution is converted at flow-date FX. CNY market value is converted at valuation-date FX. Both streams then feed the existing `PerformanceEngine`.

External-flow model when CNY activity exists:

```text
USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY
```

No-CNY activity is an exact compatibility case: reporting reduces to the established USD account and does not require FX.

Missing required FX or an open-day NAV gap makes the CNY component incomplete and the combined reporting projection `PARTIAL`. FX carry-forward is bounded to seven calendar days.

## Plan and contribution model

USD plan cycles freeze historical intent. Actual real DCA execution is a BUY linked to a cycle. DEPOSIT funds the account; it does not execute DCA.

Contribution analysis remains real BUY-lot attribution:

- INITIAL BUY linked to plan;
- cycle-linked DCA BUY;
- UNPLANNED/unclassified BUY excluded from attributed plan totals;
- global split-aware FIFO SELL can realize P/L from attributed lots.

The CNY automatic-fund-DCA reconstruction is separate and does not silently become real contribution lots.

## Modules

Current API ownership includes:

- `security` / `auth` — single-user session, CSRF, throttle;
- `instrument` — tracked ETF identity/profile;
- `marketdata` — quote/history/intraday/provider synchronization;
- `benchmark` — read-only ETF/index/equity comparisons;
- `fund` — China fund metadata/NAV/calendar/auto-DCA rules/projection;
- `fx` — persisted USD/CNY rates and synchronization;
- `transaction` — real ledger CRUD, cash replay validation, CSV import;
- `portfolio` — real USD current/historical projection and snapshots;
- `performance` — reusable performance engine and real USD source;
- `reporting` — V025 cross-currency USD reporting projection;
- `plan` — USD plans/cycles/recommendations/contribution analysis;
- `settings`, `observability`.

## Persistent schema

Flyway is the schema owner; production Hibernate uses `ddl-auto=validate`.

Recent migrations:

- `V017`: contribution constraints/backfill/audit;
- `V018`: quote-session classification;
- `V019`: invalidate untrusted snapshots;
- `V020`: experimental midnight settlement;
- `V021`: remove midnight settlement;
- `V022`: explicit cash ledger, bridge cash rows, cash-inclusive snapshots;
- `V023`: CNY fund profile + automatic-DCA rule foundation;
- `V024`: confirmed China open days for NAV gap/confirmation logic;
- `V025`: daily FX fact table.

Important current tables include:

```text
instrument
market_price_daily
market_quote_latest
fund_nav_daily
fund_profile
fund_market_calendar_day
auto_dca_rule
fx_rate_daily
instrument_split
investment_transaction
investment_plan*
portfolio_snapshot_daily
contribution_classification_audit
SPRING_SESSION*
```

`portfolio_snapshot_daily` is still only the real USD-account read model. V025 does not persist a second combined-account snapshot table.

## Decimal and time rules

- financial values: Java `BigDecimal` / PostgreSQL `NUMERIC`;
- financial API values: decimal JSON strings;
- Web financial calculations: `decimal.js-light` where arithmetic is needed;
- timestamps: UTC;
- US account/market dates: `America/New_York`;
- China fund calendar/provider semantics: `Asia/Shanghai`.

The isolated positive fractional-power operation used by CAGR may use `Math.pow` after validation.

## Migration compatibility

V022 bridge DEPOSIT/WITHDRAWAL rows preserve pre-explicit-cash economics and are real migration compatibility facts. Do not delete them as noise.

V023–V025 are forward-only. Do not rewrite published migrations.

## Deployment invariants

- PostgreSQL 18+ volume mounts at `/var/lib/postgresql`;
- major PostgreSQL upgrades use logical dump/restore;
- never use `docker compose down -v` for routine upgrade;
- provider keys remain server-side;
- live mode never falls back to demo fixtures after API failure;
- Demo mode is explicit and does not invent China-fund/FX/reporting data.

## Documentation priority

When sources conflict:

```text
current source + published migrations
> current tests
> current runtime / exact CI evidence
> living docs
> dated sa-*.md evidence
```

Historical `docs/sa-*.md` files are intentionally not rewritten as current-state documentation.