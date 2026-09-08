# DCA Terminal Calculation Rules

> Current calculation model: V025.

Financial arithmetic uses exact decimal values (`BigDecimal` / PostgreSQL `NUMERIC`) except the documented positive fractional-power boundary for CAGR. Financial API values are decimal JSON strings.

## Time and fact conventions

- US real-account business/market dates use `America/New_York`.
- China fund calendar/provider semantics use `Asia/Shanghai`.
- Database timestamps are UTC.
- Historical lookups may use the latest valid fact `<= target date` only where that fact is legitimately carry-forwardable.
- Missing required facts are never replaced by a future value, current value, zero, or a different fact type.
- Latest quote, raw close, adjusted close, fund NAV, China open day, and FX rate are distinct facts.

## Real USD cash ledger

```text
cashChange(DEPOSIT)    = +amount
cashChange(WITHDRAWAL) = -amount
cashChange(BUY)        = -(quantity * unitPrice + fee)
cashChange(SELL)       = +(quantity * unitPrice - fee)
cashChange(DIVIDEND)   = +amount
cashChange(FEE)        = -amount
cashChange(INTEREST)   = +amount
```

External capital for the real USD performance source:

```text
externalFlow(DEPOSIT)    = +amount
externalFlow(WITHDRAWAL) = -amount
externalFlow(other)      = 0
```

BUY/SELL therefore move money internally between cash and securities rather than creating investment return.

## Split-aware FIFO

For split ratio `numerator / denominator`:

```text
shares_after         = shares_before * numerator / denominator
per_share_cost_after = per_share_cost_before * denominator / numerator
```

BUY lot cost:

```text
buy cost = quantity * unitPrice + fee
```

SELL:

```text
sell proceeds = quantity * unitPrice - fee
realized P/L  = sell proceeds - FIFO cost consumed
```

A SELL exceeding available split-adjusted shares is invalid.

## Real USD portfolio values

For date `d`, replay only real ledger events/splits visible by `d`.

```text
securitiesValue = sum(open shares * selected raw market price)
cashBalance     = cash-ledger balance
marketValue     = securitiesValue + cashBalance
costBasis       = sum(open security-lot cost)
unrealizedPnl   = securitiesValue - costBasis
netInvested     = cumulative DEPOSIT - WITHDRAWAL
```

For a complete valuation:

```text
totalPnl = marketValue - netInvested
```

Account P/L therefore naturally includes realized/unrealized security return, dividends, interest, and fees while only deposits/withdrawals change external capital.

Historical account values use historical ledger replay plus that date's regular-session price. Current holdings multiplied by old prices are forbidden because that introduces look-ahead bias.

## Canonical USD performance engine

Endpoint:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Input sequence:

```text
V_t = total account value
F_t = cumulative external flow
```

Adjacent valid period:

```text
externalFlow_t = F_t - F_(t-1)
gross_t        = (V_t - externalFlow_t) / V_(t-1)
level_t        = level_(t-1) * gross_t
```

Only complete positive valuations participate. A PARTIAL current value cannot become a live performance endpoint.

### TWR

```text
rebasedLevel_t = level_t / selectedBaselineLevel
TWR            = terminal rebasedLevel - 1
```

### CAGR

```text
CAGR  = terminalLevel ^ (1 / years) - 1
years = elapsedDays / 365.2425
```

### Maximum drawdown

```text
peak_t     = max(level_0 ... level_t)
drawdown_t = level_t / peak_t - 1
maxDD      = min(drawdown_t)
```

### XIRR

Real USD XIRR cash flows:

```text
DEPOSIT    = -amount
WITHDRAWAL = +amount
valuation  = +current real USD account value
```

BUY/SELL/DIVIDEND/FEE/INTEREST are not separate XIRR flows.

The solver uses dated cash flows, deterministic bracketing, and bisection. Invalid/no-root cases return null.

## Today performance

Today is anchored to the previous completed regular-close USD portfolio point strictly before the current New York date.

```text
Today investment P/L
  = current real USD value
  - prior regular-close real USD value
  - real external flow since that close
```

V020's experimental midnight settlement was removed by V021.

## China fund automatic-DCA projection

The configured DCA amount is gross CNY cash paid. For purchase-fee rate `r`:

```text
net subscription = gross amount / (1 + r)
purchase fee     = gross amount - net subscription
shares           = net subscription / NAV
```

A derived execution requires an observed NAV on that date. A confirmed China open day with missing NAV does not receive a carry-forward NAV and does not create a purchase.

Management fee is metadata only because published mutual-fund NAV already reflects accrued fund-level expenses.

Cumulative projected shares on date `d`:

```text
shares_d = sum(derived execution shares with navDate <= d)
```

Projected CNY fund value uses the latest valid NAV on/before `d`, except a confirmed open day with missing exact NAV is considered an incomplete valuation rather than a normal carry-forward day.

## V025 FX conversion

`fx_rate_daily` currently stores USD/CNY with:

```text
1 USD = R CNY
```

Therefore:

```text
USD = CNY / R
```

The reporting projection distinguishes **flow-date FX** from **valuation-date FX**.

For each derived CNY DCA execution `i`:

```text
cnyExternalFlow_i = grossAmount_i
usdExternalFlow_i = grossAmount_i / USD_CNY(flowDate_i)
```

Historical contributions are never retranslated at today's FX.

For CNY fund value at date `t`:

```text
cnyFundValue_t = sum(cumulative shares * usable NAV_t)
cnyFundUsd_t   = cnyFundValue_t / USD_CNY(t)
```

FX uses the latest stored rate on/before the target date only within a seven-calendar-day carry window. A more stale rate is not treated as a usable conversion.

Example:

```text
2026-09-07 gross CNY contribution = 710
2026-09-07 USD/CNY = 7.10
historical external flow = USD 100

2026-09-08 fund value = CNY 781
2026-09-08 USD/CNY = 7.20
current fund value = USD 108.472222...
```

The USD 100 historical contribution stays USD 100. The later FX movement contributes to investment return.

## V025 combined USD reporting

Endpoint:

```text
GET /api/v1/reporting/multicurrency?range=1M|3M|1Y|YTD|ALL
```

This is a reporting projection, not a real multi-currency ledger.

At date `t`:

```text
combinedValueUsd_t
  = realUsdAccountValue_t
  + derivedCnyFundValueUsd_t

combinedExternalFlowUsd_t
  = realUsdCumulativeDepositMinusWithdrawal_t
  + cumulative derived CNY DCA gross flows translated at each flow-date FX

combinedPnlUsd_t
  = combinedValueUsd_t - combinedExternalFlowUsd_t
```

Combined TWR/CAGR/XIRR/drawdown use the **same** `PerformanceEngine`, supplied with the combined valuation/external-flow source.

When CNY activity exists, the reporting flow model is:

```text
USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY
```

If no CNY automatic-DCA execution exists, the reporting result must reduce exactly to the real USD account and no FX fact is required.

### Missing-data behavior

Combined reporting is `FRESH` only when the required real USD valuation, CNY NAV/open-day facts, historical CNY flow FX, and current valuation FX are complete.

If a required CNY component is missing:

- do not report a partial converted value as if complete;
- set affected combined values/flows to null where appropriate;
- degrade status to `PARTIAL`;
- do not append a fabricated live performance endpoint.

## ETF metrics

Today / 1D:

```text
ETF Today Return = latest market price / previous regular close - 1
```

1M / 3M / 1Y use adjusted close at the latest available trading date `<= target`:

```text
period return = latest adjusted close / target adjusted close - 1
```

YTD uses final prior-calendar-year adjusted close. 3Y CAGR uses elapsed calendar days/365.2425. 52-week high/low uses raw daily high/low. ETF drawdown uses adjusted close and must degrade if adjusted close is missing.

## Allocation

Real security allocation remains securities-only:

```text
actualWeight_i = securityMarketValue_i / totalSecuritiesValue
```

Cash is shown separately and is not a pseudo security. V025 Reporting fund positions are also not injected into this real USD allocation endpoint.

## USD plan cycles

Actual cycle execution remains linked real BUY cash outlay:

```text
executedAmount = sum(quantity * unitPrice + fee for linked BUYs)
```

DEPOSIT funding does not complete a cycle.

Statuses:

```text
before window                         -> UPCOMING
inside window, executed = 0          -> OPEN
inside window, 0 < executed < plan   -> PARTIAL
inside/after, executed >= plan       -> COMPLETED
after window, executed = 0           -> SKIPPED
```

## Contribution-batch analysis

Real contribution analysis remains BUY-lot based.

```text
principal = BUY quantity * unitPrice + fee
batch P/L = attributed realized P/L + openValue - openCost
batch ROI = batch P/L / principal
```

DIVIDEND, INTEREST, standalone FEE, DEPOSIT, and WITHDRAWAL remain outside batch attribution, so batch totals are not expected to equal total real account P/L without an explicit reconciliation bridge.

CNY automatic-DCA derived executions are not silently inserted into this real BUY-lot analysis.

## V022 legacy bridge semantics

V022 inserted deterministic compatibility cash events around legacy pre-cash-ledger BUY/SELL rows:

- preceding DEPOSIT for legacy BUY cash outlay;
- following WITHDRAWAL for positive legacy SELL proceeds;
- defensive DEPOSIT for unusual negative SELL proceeds.

These are migration facts and remain part of real ledger replay.