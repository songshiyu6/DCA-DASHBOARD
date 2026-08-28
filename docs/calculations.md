# DCA Terminal v1 Calculation Rules

This document is the source of truth for financial calculation behavior. All
rules use exact decimal arithmetic (`BigDecimal` in Java and `NUMERIC` in
PostgreSQL). Intermediate calculations use a high precision context; values
are rounded only at the documented display or cash-allocation boundary.

## Time and price conventions

- The market calendar and plan execution window use `America/New_York`.
- Database timestamps are UTC.
- A trading-date lookup for a target calendar date selects the latest available
  trading date `<= targetDate`.
- A missing price is not replaced by a future price, a current price, or zero.
- Performance return metrics use provider-adjusted closes where available.

## ETF metrics

### Today / 1D

```text
Today Return = latest market price / previous close - 1
```

The quote's current traded price and previous close are used. This is a market
move, not a dividend-adjusted long-term return.

### 1M, 3M, and 1Y

For each target range, calculate the calendar target date and select the latest
stored trading date on or before it. Use adjusted close for both endpoints:

```text
Period Return = latest adjusted close / target-date adjusted close - 1
```

If no endpoint exists, return null with `INSUFFICIENT_HISTORY`/`PARTIAL` rather
than using the next trading day.

### YTD

YTD is not simply the first quote observed in the current year. Select the last
available trading date in the previous calendar year and the latest adjusted
close:

```text
YTD = latest adjusted close / previous-year-last-trading-day adjusted close - 1
```

If the ETF did not have a prior-year bar, the metric is unavailable.

### 3Y CAGR

Select the adjusted close at the latest trading date on or before three years
before the latest date. Use the exact elapsed day count and a 365.2425-day
year:

```text
3Y CAGR = (end adjusted close / start adjusted close)
          ^ (365.2425 / elapsed days) - 1
```

The implementation uses a high-precision `BigDecimal` context for ratios and
integer powers. Java's standard library has no arbitrary-precision fractional
power operation, so the isolated fractional-exponent boundary converts the
positive base and exponent to finite `double`, calls `Math.pow`, and immediately
converts the result back to the application `MathContext`. This is the only
intentional floating-point boundary and is covered by tolerance-based tests.
Return null if the start value is non-positive or history is insufficient.

### 52-week high and low

Use the most recent 365 calendar days of daily bars. These two fields use raw
`high` and raw `low`, not adjusted close:

```text
52W High = max(raw high)
52W Low  = min(raw low)
```

If high/low data is missing for a required bar, mark the result partial rather
than silently substituting close.

### Drawdown

Current drawdown uses adjusted close and the running peak over all available
stored history:

```text
runningPeak[t] = max(adjustedClose[0..t])
currentDrawdown = latest adjusted close / historical running peak - 1
```

Maximum drawdown for the ETF detail's one-year view uses the latest 365
calendar days:

```text
drawdown[t] = adjustedClose[t] / runningPeak[t] - 1
maxDrawdown1Y = min(drawdown[t])
```

The v1 UI does not claim an all-history maximum drawdown field unless the
requested range is explicitly extended in a later version.

## Split-aware ledger and FIFO

A split event with ratio `numerator / denominator` applies on its effective
date. When replaying a position after that date:

```text
shares after split = shares before split * numerator / denominator
per-share cost after split = per-share cost before split
                          * denominator / numerator
```

The original transaction remains unchanged in the ledger. The replay layer
applies the event to open lots and to historical quantities. Provider-adjusted
prices must not be split-adjusted a second time.

For each instrument, valid BUY transactions create FIFO lots ordered by trade
date and the server-assigned `ledgerOrder` (with created timestamp and ID as
legacy tie-breakers). A BUY lot's cost includes its execution fee:

```text
buy lot cost = quantity * unit price + buy fee
```

A SELL consumes the oldest available lots first. Its realized P/L is:

```text
sell proceeds = quantity * unit price - sell fee
realized P/L = sell proceeds - FIFO cost of consumed shares
```

The remaining cost basis is the sum of open lot costs after split adjustment.
A SELL that exceeds available split-adjusted shares is rejected. DIVIDEND is a
positive cash-flow/income fact and does not automatically reinvest. A separate
BUY represents reinvestment. A standalone FEE has a positive `amount` field
and is tracked separately from trade execution fees.

## Portfolio values

At valuation date `d`, only transactions with `trade_date <= d` and split
events effective by `d` are replayed:

```text
market value = sum(open split-adjusted shares * raw market close)
cost basis   = sum(open lot cost)
unrealized P/L = market value - cost basis
```

Realized P/L is the accumulated FIFO result from SELL transactions. Dividend
income is the sum of DIVIDEND amounts. Trade execution fees are already
included once in lot cost or sell proceeds; standalone FEE transactions are
subtracted separately. Therefore the displayed total is:

```text
total P/L = realized P/L + unrealized P/L + dividend income
            - standalone FEE transactions
```

The UI may show total trade fees and standalone fees as separate audit fields;
it must not subtract BUY/SELL fees a second time.

`net invested` for the portfolio chart is cumulative BUY cash outlay less
SELL net proceeds. Dividends are income cash flows, not a new external
contribution. DCA contribution progress counts only BUY cash outlay linked to
the relevant cycle/plan period.

## Portfolio history

Daily `portfolio_snapshot_daily` rows are a rebuildable cache, not a fact
source. History for a requested range reuses valid snapshots that cover a date
and replays missing dates in order. A non-empty snapshot list must not short-
circuit the range. Backdated ledger mutations invalidate snapshots from the
affected date forward; earlier dates stay.

For every requested snapshot date, the service replays transactions known on
that date and uses that date's market price. A transaction bought in 2026 must
not affect a 2025 portfolio value. Current holdings multiplied by old prices
are explicitly forbidden because that introduces look-ahead bias.

If one or more required daily bars are missing, the service may carry forward
the previous valid EOD close for continuity only when it marks the snapshot
`PARTIAL` and records the missing instruments. It must not carry a price before
the instrument's first available bar.

## XIRR / personal return

XIRR uses dated cash flows and the 365-day year basis:

```text
BUY       = -(quantity * unit price + fee)
SELL      =  (quantity * unit price - fee)
DIVIDEND  =  amount
FEE       = -amount
valuation = +current market value on the valuation date
```

For cash flow `CF_i` on date `d_i`, with `d_0` the first cash-flow date:

```text
NPV(r) = sum(CF_i / (1 + r)^((d_i - d_0) / 365))
```

The implementation brackets roots over a deterministic bounded probe range and
solves the selected sign-changing interval with bisection. If multiple roots
exist, v1 selects the root whose interval midpoint has the smallest absolute
rate, then the lower interval as a deterministic tie-breaker. This is a product
convention, not a claim that XIRR is globally unique. If cash flows do not
contain both positive and negative values, no bracket is found, or the result
is non-finite, return null with an explanatory status instead of NaN or an
HTTP 500. The rate is not capped for calculation; formatting applies a
reasonable display precision.

## Allocation and drift

For active-plan assets:

```text
actualWeight[i] = current market value[i] / total portfolio market value
drift[i]        = actualWeight[i] - targetWeight[i]
```

Unplanned holdings remain visible in the overall portfolio. They are marked
unplanned and do not silently change the active plan's target weights. If the
portfolio value is zero, actual weights and drift are null.

## Plan cycles and execution

For a monthly cycle, `planned_amount` is the frozen plan budget. `executed_amount`
is the sum of linked BUY cash outlay:

```text
BUY execution = quantity * unit price + fee
```

The real transaction ledger accepts only trade dates on or before the
application-local current date. A future-dated transaction is rejected with
`FUTURE_TRADE_DATE_NOT_ALLOWED`; cycle status and executed amount never use a
future transaction to advance a cycle before its execution window.

Statuses are deterministic:

```text
before execution window, no matter the execution  -> UPCOMING
inside window, executed amount = 0                -> OPEN
inside window, 0 < executed < planned             -> PARTIAL
inside/after window, executed >= planned          -> COMPLETED
after window, executed = 0                         -> SKIPPED
```

The UI may show over-execution as `COMPLETED` with an overage indicator; it
must not reduce the executed amount to the plan budget.

Annual execution rate uses started cycles and caps the display at 100%:

```text
execution rate = sum(min(executed, planned)) / sum(planned)
```

Upcoming cycles are excluded from the denominator. Annual contribution
progress can show the actual amount above the annual planned amount while the
rate remains understandable.

## Contribution-first recommendation

For contribution `C`, current total portfolio value `V`, current value of
asset `i` as `A_i`, and target weight `w_i`:

```text
new portfolio value = V + C
target value[i]     = (V + C) * w_i
gap[i]              = target value[i] - A_i
positive gap[i]     = max(gap[i], 0)
```

If the sum of positive gaps is non-zero, allocate the contribution by positive
gap proportion. Assets with a negative gap receive zero. This is contribution-
first rebalancing: it does not sell an overweight asset.

Monetary suggestions are rounded to cents using `HALF_UP`. The final cent
remainder is allocated by largest remainder, with deterministic symbol order
as the tie-breaker. The sum of suggestions must equal `C` exactly. If a plan
asset has no reliable current price, the response is `PARTIAL` and the
recommendation is disabled rather than estimated.

## Display rounding and auditability

Display formatting is separate from calculation precision:

- prices and monetary values: two decimal places with currency symbol;
- quantities: up to eight decimal places, trimming trailing zeroes;
- rates: two decimal places by default;
- AUM: compact units only after exact calculation.

Raw decimal values remain available in API responses and database rows. Unit
tests must cover YTD boundary dates, missing trading days, adjusted versus raw
prices, split quantities, FIFO partial sells, fees, XIRR invalid roots,
weight tolerance, cycle status, and recommendation sum/overweight behavior.
