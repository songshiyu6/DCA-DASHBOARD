# DCA Terminal API

> Current baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`.
> Current Flyway chain: `V001`–`V022`.

This document describes the HTTP contract on current `main`. Financial `BigDecimal` values are serialized as plain decimal strings. Dates are ISO `YYYY-MM-DD`; timestamps are UTC ISO-8601 strings. Null response properties may be omitted.

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

Main routes:

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

Tracked-instrument domain remains ETF-only. Market quote, adjusted close, and NAV are distinct fields/facts.

`range=1D` is on-demand intraday data and is not persisted as permanent five-minute history. Persisted-history ranges include `1W`, `1M`, `3M`, `YTD`, `1Y`, `3Y`, `5Y`, `ALL`.

Freshness values include:

```text
FRESH
STALE
PARTIAL
UNAVAILABLE
INSUFFICIENT_HISTORY
```

## Benchmarks

Read-only benchmark routes:

```text
GET /api/v1/benchmarks/search?q=...
GET /api/v1/benchmarks/history?symbol=...&type=ETF|INDEX|EQUITY&range=...
```

Benchmark identities are isolated from tracked instruments. Adding a benchmark does not create portfolio facts. Yahoo search/history currently supports ETF, INDEX, and EQUITY benchmark types.

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

### Transaction request

Representative fields:

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

### Contribution attribution

Contribution source may be `INITIAL`, `DCA`, `UNPLANNED`, or null where permitted.

BUY rules:

- a BUY linked to `planCycleId` is DCA and the plan is inferred from the cycle;
- DCA BUY requires a cycle;
- INITIAL BUY requires `contributionPlanId` and the plan start date;
- UNPLANNED BUY must not carry a contribution plan.

DEPOSIT rules:

- DEPOSIT cannot link directly to a plan cycle;
- DCA funding DEPOSIT may carry `contributionType=DCA` plus `contributionPlanId`;
- INITIAL funding DEPOSIT uses `INITIAL` + plan and must satisfy the plan start-date rule;
- UNPLANNED funding has no plan.

Current contribution-analysis batches remain BUY-lot based; a DEPOSIT does not by itself complete a DCA cycle.

### CSV

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

### Summary semantics

Current summary exposes legacy/core fields plus cash breakdown:

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

Historical points expose total `marketValue`, `netInvested`, `costBasis`, `unrealizedPnl`, `dataStatus`, plus `securitiesValue` and `cashBalance`.

## Dashboard

```text
GET /api/v1/dashboard
```

The dashboard combines current portfolio views, all portfolio history, active-plan next DCA/progress, holdings, and allocation.

## Performance

Canonical server performance endpoint:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Response fields include:

```text
range
requestedStartDate
baselineDate
inceptionDate
endpointDate
asOf
twr
cagr
xirr
maximumDrawdown
dataStatus
liveEndpointIncluded
externalFlowModel
points[]
```

Each point includes date, optional live `asOf`, level, returnRate, pointType (`REGULAR_CLOSE` or `LIVE`), and dataStatus.

Current external-flow model is `CASH_LEDGER_DEPOSIT_WITHDRAWAL`.

A live point is included only when current total account valuation is complete, positive, and `FRESH`.

## Plans

```text
GET    /api/v1/plans
GET    /api/v1/plans/{id}
POST   /api/v1/plans
PUT    /api/v1/plans/{id}
POST   /api/v1/plans/{id}/archive
DELETE /api/v1/plans/{id}          # archive semantics
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

Analysis returns plan-attributed INITIAL/DCA BUY batches, account-wide unclassified BUY queue, bucket totals, freshness, and as-of date.

Classification commit is a two-phase workflow: preview exact target rows, then commit a matching preview hash atomically and persist audit records.

## Settings

Settings APIs expose non-secret application/provider configuration only. Provider keys are represented as configured/unconfigured capability and are never returned to the browser.

## Wire precision

Financial response values are decimal strings even when their underlying Java type is `BigDecimal`. Counts, HTTP statuses, ledger order, and calendar-day values remain JSON numbers.

Do not introduce a frontend conversion through JavaScript binary `number` for financial storage/calculation boundaries.
