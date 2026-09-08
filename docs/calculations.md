# DCA Terminal Calculation Rules

> Current baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`.
> This document reflects the post-V022 cash-ledger model.

All financial arithmetic uses exact decimal values (`BigDecimal` / PostgreSQL `NUMERIC`) except the documented positive fractional-power boundary for CAGR. API financial values are decimal JSON strings; Web calculations normalize them with `decimal.js-light`.

## Time and price conventions

- Business date and US plan/market decisions use `America/New_York`.
- Database timestamps are UTC.
- A historical lookup for target date `d` uses the latest available trading date `<= d`.
- A missing required value is never replaced by a future price, current price, NAV, or zero.
- Current account valuation can use the newest valid regular/pre/post/extended/overnight quote.
- Historical portfolio replay and persisted snapshots use regular-session raw market closes.
- ETF historical return metrics use adjusted close where documented.

## Cash ledger

Cash is a projection of the ordered transaction ledger:

```text
cashChange(DEPOSIT)    = +amount
cashChange(WITHDRAWAL) = -amount
cashChange(BUY)        = -(quantity * unitPrice + fee)
cashChange(SELL)       = +(quantity * unitPrice - fee)
cashChange(DIVIDEND)   = +amount
cashChange(FEE)        = -amount
cashChange(INTEREST)   = +amount
```

All cash events are replayed in trade-date / ledger-order sequence.

The external-capital stream used for portfolio performance is deliberately narrower:

```text
externalFlow(DEPOSIT)    = +amount
externalFlow(WITHDRAWAL) = -amount
externalFlow(other)      = 0
```

Therefore BUY/SELL do not create investment performance merely by moving money between cash and securities.

## Split-aware FIFO

A split with ratio `numerator / denominator` changes open lot shares while preserving lot total cost:

```text
shares_after = shares_before * numerator / denominator
per_share_cost_after = per_share_cost_before * denominator / numerator
```

BUY lot cost:

```text
buy cost = quantity * unitPrice + fee
```

SELL proceeds and realized P/L:

```text
sell proceeds = quantity * unitPrice - fee
realized P/L  = sell proceeds - FIFO cost consumed
```

A SELL exceeding available split-adjusted shares is invalid.

## Portfolio values

For valuation date `d`, replay only ledger events and splits visible by `d`.

```text
securitiesValue = sum(open shares * selected raw market price)
cashBalance     = cash-ledger balance
marketValue     = securitiesValue + cashBalance
costBasis       = sum(open security-lot cost)
unrealizedPnl   = securitiesValue - costBasis
netInvested     = cumulative DEPOSIT - WITHDRAWAL
```

`marketValue` now means total account value, despite the historical field name.

When the security valuation is complete:

```text
totalPnl = marketValue - netInvested
```

This account-level P/L naturally includes realized security gains/losses, unrealized P/L, dividends, interest, and standalone fees because all of them affect total account value while only deposits/withdrawals affect external capital.

The backend still exposes component fields such as realized P/L, unrealized P/L, dividend income, interest income, and total fees for audit/presentation. Those components must not be recombined in a way that double-counts trade fees.

## Current vs historical valuation

Current security prices are loaded from live/latest quotes when possible. If a live refresh cannot produce a usable price, stored quote or prior daily close may be used with degraded freshness according to current portfolio logic.

Historical daily account values use the ledger as it existed on each date plus that date's regular-session price. Current holdings multiplied by old prices are forbidden because they introduce look-ahead bias.

Snapshots are rebuildable read models. Backdated transaction changes invalidate snapshots from the affected date forward.

## Portfolio performance engine

Canonical endpoint:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

The engine consumes a sequence of account valuations:

```text
V_t = total account value at t
F_t = cumulative external flow at t
```

For adjacent valid valuations, period gross factor is:

```text
externalFlow_t = F_t - F_(t-1)
gross_t        = (V_t - externalFlow_t) / V_(t-1)
level_t        = level_(t-1) * gross_t
```

Only complete positive valuations participate. A PARTIAL current valuation is not appended as a live endpoint.

### Inception baseline

If the requested range starts at or before the first valuation, the theoretical inception level begins from cumulative external capital rather than forcing the first visible account mark to exactly 1. This preserves performance earned between initial funding and the first regular-close/live valuation.

### TWR

For the selected range, levels are rebased to the selected baseline:

```text
rebasedLevel_t = level_t / baselineLevel
TWR            = terminal rebasedLevel - 1
```

TWR removes deposits/withdrawals from investment performance.

### CAGR

CAGR is annualized from inception level history using elapsed calendar days and a 365.2425-day year:

```text
CAGR = terminalLevel ^ (1 / years) - 1
years = elapsedDays / 365.2425
```

The implementation validates a positive terminal level and uses an isolated `Math.pow` boundary for the fractional exponent.

### Maximum drawdown

For selected performance points:

```text
peak_t     = max(level_0 ... level_t)
drawdown_t = level_t / peak_t - 1
maxDD      = min(drawdown_t)
```

### XIRR

Current portfolio XIRR uses only external capital events plus current total account value:

```text
DEPOSIT    = -amount
WITHDRAWAL = +amount
valuation  = +current total account value
```

BUY, SELL, DIVIDEND, FEE, and INTEREST are internal account events and are not separate XIRR cash flows in the post-V022 model.

The XIRR solver uses dated cash flows with a 365-day exponent basis, deterministic root bracketing, and bisection. If there is no valid sign-changing bracket or result is non-finite, return null rather than NaN/HTTP 500.

## Today performance

Today is anchored to the previous completed regular-close portfolio point strictly before the current New York business date.

Conceptually:

```text
Today investment P/L = current total value
                     - prior regular-close total value
                     - external capital flow since that close
```

This baseline is retained throughout pre-market, regular trading, and after-hours on the same New York date. It rolls on the next New York calendar day.

The experimental midnight-settlement runtime path was removed by V021.

## ETF metrics

### Today / 1D

```text
ETF Today Return = latest market price / previous regular close - 1
```

### 1M / 3M / 1Y

Use adjusted close at the latest available trading date `<=` target date:

```text
period return = latest adjusted close / target adjusted close - 1
```

### YTD

Use the final available prior-calendar-year adjusted close as baseline:

```text
YTD = latest adjusted close / previous-year final adjusted close - 1
```

### 3Y CAGR

```text
3Y CAGR = (end adjusted close / start adjusted close)
          ^ (365.2425 / elapsedDays) - 1
```

### 52-week high / low

Use raw daily `high` / `low` over the latest 365 calendar days.

### ETF drawdown

Use adjusted close and running peak. Missing adjusted close must degrade the metric instead of substituting raw close.

## Allocation

Security holdings allocation is a securities-only concept:

```text
actualWeight_i = securityMarketValue_i / totalSecuritiesValue
```

Cash is displayed separately and is not a pseudo security in the allocation service.

For plan assets:

```text
drift_i = actualWeight_i - targetWeight_i
```

Unplanned holdings remain visible but do not silently change plan target weights.

## Plan cycles

A monthly cycle freezes plan intent. Actual execution is the cash outlay of linked BUY rows:

```text
executedAmount = sum(quantity * unitPrice + fee for linked BUYs)
```

DEPOSIT funding does not complete a cycle.

Statuses remain deterministic:

```text
before window                         -> UPCOMING
inside window, executed = 0          -> OPEN
inside window, 0 < executed < plan   -> PARTIAL
inside/after, executed >= plan       -> COMPLETED
after window, executed = 0           -> SKIPPED
```

An actual INITIAL BUY in the plan start month can suppress ordinary DCA execution for that month according to current plan rules.

## Contribution-batch analysis

Contribution analysis remains BUY-lot based.

Attributed batches:

- `INITIAL`: explicit initial BUY for the plan;
- `DCA`: BUY linked to a plan cycle;
- `UNPLANNED` and unclassified BUYs are excluded from attributed plan totals.

Principal:

```text
principal = BUY quantity * unitPrice + fee
```

SELL consumes global FIFO lots and assigns realized P/L to the lot's original batch.

For a complete open lot valuation:

```text
batch P/L = attributed realized P/L + openValue - openCost
batch value = principal + batch P/L
batch ROI = batch P/L / principal
```

Current contribution analysis deliberately excludes DIVIDEND, INTEREST, account-level FEE, DEPOSIT, and WITHDRAWAL from batch attribution. These facts still affect account cash/performance, so batch totals are not expected to equal full account P/L without an explicit reconciliation bridge.

The current `averageMarketDays`/weighted-day field is cost-weighted calendar days, not exchange trading days.

## V022 legacy bridge semantics

Before explicit cash existed, legacy BUY/SELL rows implicitly injected or removed external money. V022 inserts synthetic bridge events to preserve that economic history:

- before each positive legacy BUY cash outlay: matching DEPOSIT;
- after each positive legacy SELL net proceeds: matching WITHDRAWAL;
- defensive DEPOSIT for a legacy SELL whose fee exceeded gross proceeds.

These bridge events make the post-V022 external-flow model economically equivalent to the old implicit model for migrated accounts. They must remain part of ledger replay.
