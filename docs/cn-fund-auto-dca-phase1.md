# Phase 1: China mutual funds and rule-driven automatic DCA

This document records the first implementation slice for RMB China mutual-fund DCA. It is intentionally narrower than the final multi-currency portfolio design.

## Implemented semantics

- China mutual funds use `instrument_type=MUTUAL_FUND` and `currency=CNY`.
- `fund_profile` stores annual management-fee metadata, share class, and configurable share-confirmation trading-day lag.
- Management fee is display/reference metadata only. Published NAV already reflects fund-level accrued expenses, so portfolio calculations must not deduct it again.
- `auto_dca_rule` is the source of truth for automatic DCA history.
- Phase 1 supports `DAILY_FUND_TRADING_DAY` only.
- A rule stores CNY amount, start/end date, purchase-fee rate, and enabled state.
- Editing a rule with `PUT` rewrites its derived history. There are no persisted daily BUY transactions to delete or repair.
- Historical automatic-DCA executions are derived from observed fund NAV dates inside the rule interval.
- NAV date is the purchase valuation date. Confirmation date is derived separately using the fund's configurable `confirmationTradingDays`.
- The configured DCA amount is treated as total cash paid. For a front-end purchase fee rate `r`:

```text
net subscription = gross amount / (1 + r)
purchase fee     = gross amount - net subscription
shares           = net subscription / confirmed NAV
```

- The projection API defaults to monthly aggregation and supports yearly aggregation.
- Daily derived rows are hidden by default and returned only when `includeDaily=true` is requested.

## API

Fund metadata and confirmed/manual NAV history:

```text
GET  /api/v1/funds
POST /api/v1/funds
GET  /api/v1/funds/{id}
PUT  /api/v1/funds/{id}
GET  /api/v1/funds/{id}/nav
PUT  /api/v1/funds/{id}/nav
```

Automatic DCA rules and analysis:

```text
GET  /api/v1/auto-dca/rules
POST /api/v1/auto-dca/rules
GET  /api/v1/auto-dca/rules/{id}
PUT  /api/v1/auto-dca/rules/{id}
GET  /api/v1/auto-dca/rules/{id}/projection?groupBy=MONTH|YEAR&includeDaily=false
```

The projection response contains period execution count, confirmed count, total invested amount, purchase fees, net subscription amount, acquired shares, average NAV, average cost/share, latest NAV, current value, P/L, and return rate.

## Important Phase 1 boundary

The historical trading-day source is currently `OBSERVED_FUND_NAV_DATES`: a valid NAV observation is treated as evidence that the fund produced a valuation/open-day result. This correctly avoids fabricating purchases on weekends and holidays when the NAV series is complete, but it cannot distinguish a real closed day from a missing-provider row.

A dedicated China fund calendar/provider gap audit belongs in the next phase before future-schedule forecasting is presented as authoritative.

China mutual funds are also kept out of the legacy tracked-US-ETF feed in this phase. The existing ETF market-data scheduler must not attempt to synchronize a domestic mutual fund through the US ETF providers.

## Not implemented yet

- live/historical FX and USD reporting conversion;
- CNY real transaction/cash-ledger support;
- automatic fund NAV provider ingestion;
- future China exchange/fund-open calendar forecasting;
- UI for fund setup and automatic-DCA rules;
- combined AUTO vs MANUAL investment analysis;
- rule versioning for “change from this date forward” while preserving an earlier rule segment;
- integration of derived mutual-fund holdings into total portfolio TWR/XIRR.
