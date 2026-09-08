# DCA Terminal API

> Current Flyway chain: `V001`–`V024`.

Financial `BigDecimal` values are serialized as plain decimal strings. Dates are ISO `YYYY-MM-DD`; timestamps are UTC ISO-8601 strings. Null response properties may be omitted.

## Health and auth

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/health` | public minimal health |
| GET | `/api/v1/auth/session` | public session status |
| GET | `/api/v1/auth/csrf` | CSRF bootstrap |
| POST | `/api/v1/auth/login` | login |
| POST | `/api/v1/auth/logout` | authenticated logout |

With security enabled, application mutations require session + CSRF.

## Instruments / ETFs

```text
GET    /api/v1/instruments
GET    /api/v1/instruments/search?q=...
POST   /api/v1/instruments
GET    /api/v1/instruments/{symbol}
DELETE /api/v1/instruments/{symbol}
GET    /api/v1/instruments/{symbol}/quote
GET    /api/v1/instruments/{symbol}/metrics
GET    /api/v1/instruments/{symbol}/prices?range=...
POST   /api/v1/instruments/{symbol}/sync
POST   /api/v1/instruments/{symbol}/sync/full
GET    /api/v1/instruments/providers
```

The legacy tracked-instrument route remains ETF-only. China mutual funds use the dedicated `/funds` domain and cannot accidentally enter the US ETF market-data scheduler.

`range=1D` is on-demand intraday data and is not persisted as permanent five-minute history. Persisted-history ranges include `1W`, `1M`, `3M`, `YTD`, `1Y`, `3Y`, `5Y`, `ALL`.

Freshness values include `FRESH`, `STALE`, `PARTIAL`, `UNAVAILABLE`, and `INSUFFICIENT_HISTORY`.

## China mutual funds

Fund metadata and NAV:

```text
GET  /api/v1/funds
GET  /api/v1/funds/{id}
POST /api/v1/funds
PUT  /api/v1/funds/{id}
GET  /api/v1/funds/{id}/nav
PUT  /api/v1/funds/{id}/nav
POST /api/v1/funds/{id}/sync?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
GET  /api/v1/funds/{id}/calendar?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

Fund creation fields include code, name, annual management-fee rate, configurable confirmation trading-day lag, and optional share class. Current automatic provider sync supports six-digit China fund codes.

`PUT /nav` is an explicit observed NAV fact. `POST /sync` fetches fund NAV history plus confirmed Shanghai-market open dates before persisting either dataset. Provider failure does not delete existing local NAV/calendar facts.

The default explicit sync range is one year ending on the current China date; one request is capped at 5,500 days. The initial provider adapter is EastMoney and is treated as an unofficial external source.

Calendar audit returns:

```text
calendarCode
startDate
endDate
source
calendarAvailable
expectedTradingDays
navDays
missingNavDates[]
```

A confirmed open date without NAV is a data gap. It never receives a carry-forward NAV and never creates an automatic-DCA execution.

Automatic-DCA rules:

```text
GET  /api/v1/auto-dca/rules
GET  /api/v1/auto-dca/rules/{id}
POST /api/v1/auto-dca/rules
PUT  /api/v1/auto-dca/rules/{id}
GET  /api/v1/auto-dca/rules/{id}/projection?groupBy=MONTH|YEAR&includeDaily=false|true
```

`auto_dca_rule` is the source fact. Derived daily purchases are not `investment_transaction` rows. Editing the rule rewrites/recomputes its whole projection history.

An execution requires an observed fund NAV. Persisted China open days are used for T+n confirmation timing. If an older execution date is outside the persisted calendar coverage, confirmation timing falls back to observed NAV dates; the projection response exposes the `tradingDaySource` used.

The configured DCA amount is gross cash paid:

```text
net subscription = gross amount / (1 + purchaseFeeRate)
purchase fee     = gross amount - net subscription
shares           = net subscription / NAV
```

Fund management fee remains metadata only because published NAV already reflects accrued fund-level expenses.

CNY mutual funds remain outside the real USD cash ledger and portfolio performance engine in this phase.

## Benchmarks

```text
GET /api/v1/benchmarks/search?q=...
GET /api/v1/benchmarks/history?symbol=...&type=ETF|INDEX|EQUITY&range=...
```

Benchmark identities are isolated from tracked instruments. Adding a benchmark does not create portfolio facts.

## Transactions

```text
GET    /api/v1/transactions?symbol=...&from=...&to=...
GET    /api/v1/transactions/{id}
POST   /api/v1/transactions
PUT    /api/v1/transactions/{id}
DELETE /api/v1/transactions/{id}
POST   /api/v1/transactions/import/preview
POST   /api/v1/transactions/import/commit
```

Current transaction types:

```text
DEPOSIT
WITHDRAWAL
INTEREST
BUY
SELL
DIVIDEND
FEE
```

Representative BUY request:

```json
{
  "instrumentSymbol": "VOO",
  "transactionType": "BUY",
  "tradeDate": "2026-09-04",
  "quantity": "1.25",
  "unitPrice": "620.00",
  "amount": null,
  "fee": "0",
  "planCycleId": null,
  "contributionType": "UNPLANNED",
  "contributionPlanId": null,
  "notes": "manual entry"
}
```

Rules:

- BUY/SELL require positive quantity and non-negative unit price and do not accept `amount`.
- DIVIDEND/FEE require non-negative `amount`.
- DEPOSIT/WITHDRAWAL/INTEREST require positive `amount`, do not accept quantity/unit price, and are account-level.
- BUY/SELL/DIVIDEND require an instrument.
- FEE may optionally carry an instrument.
- DEPOSIT/WITHDRAWAL/INTEREST must not carry an instrument.
- Future trade dates are rejected using the New York business date.
- `fee` is only meaningful for BUY/SELL; non-trade cash events use `amount`.

Contribution source may be `INITIAL`, `DCA`, `UNPLANNED`, or null where permitted. Current contribution-analysis batches remain BUY-lot based; a DEPOSIT does not by itself complete a DCA cycle.

CSV supports the transaction type set above and fields such as:

```text
date,type,symbol,quantity,price,fee,amount,plan_cycle_id,contribution_type,contribution_plan_id,notes
```

Server preview/commit is authoritative for validation, duplicate fingerprinting, row limits, tracked-symbol rules, contribution attribution, ledger FIFO validity, and cash replay validity.

## Portfolio

```text
GET  /api/v1/portfolio/summary
GET  /api/v1/portfolio/holdings
GET  /api/v1/portfolio/allocation
GET  /api/v1/portfolio/history?range=...
POST /api/v1/portfolio/rebuild-snapshot
```

Current summary semantics:

```text
marketValue      total account value = securitiesValue + cashBalance
securitiesValue  security-only current value
cashBalance      ledger-derived cash
netInvested      cumulative DEPOSIT - WITHDRAWAL
interestIncome   cumulative INTEREST
costBasis        open security-lot cost
unrealizedPnl    security-only unrealized P/L
realizedPnl      FIFO realized security P/L
dividendIncome   dividend facts
totalFees        ledger fee audit field
totalPnl         marketValue - netInvested when complete
xirr             money-weighted return on DEPOSIT/WITHDRAWAL + current value
```

`marketValue` is kept for compatibility but now means total account value.

## Dashboard

```text
GET /api/v1/dashboard
```

The dashboard combines current portfolio views, all portfolio history, active-plan next DCA/progress, holdings, and allocation.

## Performance

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Response fields include range, requested/baseline/inception/endpoint dates, as-of, TWR, CAGR, XIRR, maximum drawdown, data status, live-endpoint flag, external-flow model, and points.

Current external-flow model is `CASH_LEDGER_DEPOSIT_WITHDRAWAL`. A live point is included only when current total account valuation is complete, positive, and `FRESH`.

## Plans

```text
GET    /api/v1/plans
GET    /api/v1/plans/{id}
POST   /api/v1/plans
PUT    /api/v1/plans/{id}
POST   /api/v1/plans/{id}/archive
DELETE /api/v1/plans/{id}
GET    /api/v1/plans/{id}/cycles
GET    /api/v1/plans/{id}/cycles/{period}
GET    /api/v1/plans/{id}/recommendation?amount=...
GET    /api/v1/plans/{id}/progress
```

Current UI/product assumes one active monthly USD plan. Cycle intent is frozen and actual execution comes from linked BUY rows.

## Contributions

```text
GET  /api/v1/plans/{planId}/contribution-analysis
POST /api/v1/plans/{planId}/contribution-classifications/preview
POST /api/v1/plans/{planId}/contribution-classifications/commit
GET  /api/v1/plans/{planId}/contribution-classifications/audit
```

Analysis returns plan-attributed INITIAL/DCA BUY batches, account-wide unclassified BUY queue, bucket totals, freshness, and as-of date. Classification commit is a preview/commit workflow with an exact preview hash and audit records.

## Settings

Settings APIs expose non-secret application/provider configuration only. Provider keys are represented as configured/unconfigured capability and are never returned to the browser.

## Wire precision

Financial response values are decimal strings even when their underlying Java type is `BigDecimal`. Counts, HTTP statuses, ledger order, and calendar-day values remain JSON numbers.

Do not introduce a frontend conversion through JavaScript binary `number` for financial storage/calculation boundaries.