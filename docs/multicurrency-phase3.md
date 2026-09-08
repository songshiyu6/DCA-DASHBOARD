# Multi-currency reporting — Phase 3

This document defines the V025 reporting projection that combines the established real USD account with CNY China-fund automatic-DCA projections without pretending that the real transaction ledger is already multi-currency.

## Scope

Phase 3 adds:

- persisted daily USD/CNY FX facts;
- explicit and scheduled FX synchronization;
- a USD reporting projection over the real USD account plus CNY automatic-fund-DCA history;
- combined USD value, external flow, P/L, TWR, CAGR, XIRR, and drawdown;
- a dedicated Web Reporting workspace.

Phase 3 does **not** add:

- CNY cash balances;
- manual real CNY fund transactions;
- broker/order execution;
- arbitrary reporting currencies;
- a second editable portfolio or holdings source.

The established `/portfolio/*` and `/performance/portfolio` endpoints keep their real USD-account semantics.

## Source facts

Three market facts remain separate:

```text
US security price / close
fund unit NAV
USD/CNY FX rate
```

V025 creates `fx_rate_daily` with:

```text
base_currency
quote_currency
rate_date
rate
source
retrieved_at
```

The current pair is `USD/CNY` with the explicit convention:

```text
1 USD = rate CNY
```

The initial adapter uses Yahoo symbol `CNY=X`. It is a reporting market rate, not an official PBOC fixing and not a bank conversion quote.

## Conversion rules

For a CNY amount `C` and USD/CNY rate `R`:

```text
USD value = C / R
```

Two different dates matter and must never be collapsed:

1. **External-flow date** — each synthetic CNY automatic-DCA gross contribution is converted with the FX rate on or immediately before that execution date.
2. **Valuation date** — CNY fund market value is converted with the FX rate on or immediately before the valuation date.

This is what makes FX movement investment performance rather than an artificial deposit/withdrawal.

Example:

```text
2026-09-07: CNY contribution 710; USD/CNY 7.10 -> external flow USD 100
2026-09-08: fund value CNY 781; USD/CNY 7.20 -> market value USD 108.4722...
```

The historical contribution remains USD 100. It is never recomputed using the later 7.20 rate.

## CNY subportfolio source

The CNY side is still a synthetic reconstruction:

```text
auto_dca_rule
+ observed fund_nav_daily
+ fund_market_calendar_day
          |
          v
  derived daily purchases
          |
          v
 cumulative fund shares
```

Every derived execution's `grossAmount` is treated as external capital entering this synthetic subportfolio. It is **not** an `investment_transaction` and does not affect the real USD cash ledger.

Editing an automatic-DCA rule still has `REWRITE_HISTORY` semantics. Therefore Phase 3 combined history is a reporting projection and can change when the rule is edited.

## Combined reporting model

At date `t`:

```text
USD real value_t = established cash-inclusive USD portfolio value
CNY fund value_t = sum(cumulative derived shares * latest usable NAV)
CNY fund USD_t    = CNY fund value_t / USD_CNY_t
combined USD_t    = USD real value_t + CNY fund USD_t
```

Cumulative external flow is:

```text
USD real external flow_t
+ sum(CNY automatic-DCA gross contributions converted at each flow-date FX)
```

Combined P/L is:

```text
combined P/L_t = combined USD_t - combined cumulative external flow_t
```

The combined performance source then feeds the same `PerformanceEngine` used by the established USD portfolio. Phase 3 does not introduce a second TWR/XIRR implementation.

The reporting external-flow model is:

```text
USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY
```

## Missing-data behavior

Missing facts are never guessed.

- Missing FX for a CNY execution means the CNY flow stream is incomplete.
- Missing FX for a held CNY fund means converted value is incomplete.
- FX carry-forward is bounded to seven calendar days; older stored FX is not treated as current.
- A confirmed China open day without an exact fund NAV remains a NAV gap and makes that CNY valuation incomplete.
- An incomplete CNY component makes the combined reporting status `PARTIAL` and prevents a fabricated live performance endpoint.

A China-closed date can carry the previous NAV because no new fund NAV is expected. An open date with missing NAV cannot.

## No-CNY compatibility invariant

If no automatic CNY DCA history exists, `/api/v1/reporting/multicurrency` must reduce to the established USD account:

```text
combinedValueUsd        = USD marketValue
combinedExternalFlowUsd = USD netInvested
combinedPnlUsd          = USD totalPnl
CNY values              = 0
performance             = existing USD PerformanceEngine output
```

No FX fact is required in this case.

## FX synchronization

APIs:

```text
GET  /api/v1/fx/usd-cny?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
POST /api/v1/fx/usd-cny/sync?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
```

The Web Reporting workspace also exposes an explicit FX refresh action.

A scheduler refreshes a recent rolling window. Network/provider work happens during sync, not during reporting reads. Reporting reads local persisted facts only.

Provider failure must not delete existing FX rows. A recent provider response with no usable rates is treated as failure rather than as evidence that the exchange rate does not exist.

## Web boundary

The existing Dashboard remains the real USD-account workspace.

The new Reporting workspace is deliberately separate and labels itself as a USD reporting projection. It shows:

- combined USD value and P/L;
- combined external flow;
- TWR/CAGR/XIRR/max drawdown;
- real USD account value;
- CNY fund value and USD conversion;
- current stored USD/CNY fact and date;
- per-fund projected position values.

Demo mode does not invent CNY or FX data and does not expose the Reporting navigation item.

## Next step

A later phase may introduce real CNY cash and manual CNY transactions. That work must extend cash/FIFO/transaction validation deliberately and replace the synthetic fund subportfolio where real facts exist. It must not silently reinterpret V025 reporting projections as real ledger events.
