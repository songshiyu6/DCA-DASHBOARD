# DCA Terminal Market Data

> Current market-data model: V025.

## Provider boundary

Provider-specific HTTP behavior stays behind adapters. Current roles:

- Yahoo Finance: default US quote/history/search/benchmark source and V025 USD/CNY reporting-FX source;
- Twelve Data: optional configured fallback for supported US market-data operations;
- Alpha Vantage: optional profile capability;
- EastMoney: separate China mutual-fund NAV + Shanghai open-day adapter.

Provider keys, Yahoo proxy configuration, cookies/crumbs, and credentials remain server-side.

EastMoney and Yahoo `CNY=X` are external data sources, not authoritative user transaction facts. Provider failure must not be converted into financial facts.

## Facts remain separate

Never collapse these into one generic price:

```text
latest traded quote
regular-session daily market price
provider adjusted close
fund unit NAV
confirmed China open day
USD/CNY daily FX rate
split event
```

Persistence:

```text
market_quote_latest          latest quote
market_price_daily           regular OHLCV + optional adjusted close
fund_nav_daily               observed mutual-fund NAV
fund_market_calendar_day     confirmed China open date
fx_rate_daily                observed reporting FX
instrument_split             split event
```

NAV is never replaced with market price. FX is never inferred from a fund/security price. Adjusted close is not a substitute for user dividend facts.

## US current quote path

Current real USD portfolio valuation prefers the newest valid timestamped quote candidate among regular, pre-market, post-market, extended, and overnight sessions.

The quote compares against previous regular close, so after-hours movement may change live real account value/Today P/L without rewriting regular-close history.

If live retrieval fails, an older stored quote/daily close may be used only with the existing degraded freshness rules. Missing price is never zero.

## US daily history

`market_price_daily` stores regular-session OHLCV, optional adjusted close, and source.

- historical real portfolio replay uses raw regular close;
- ETF return/drawdown metrics use adjusted close where documented;
- history sync is incremental;
- bounded full resync fetches before persistence and never clears old rows first.

## 1D intraday

`GET /api/v1/instruments/{symbol}/prices?range=1D` remains on-demand and non-persistent.

For Yahoo US ETF normalization:

- business/session zone is `America/New_York`;
- provider current-trading-period boundaries are respected;
- `[start,end)` session boundaries are used;
- invalid/missing exchange timezone falls back to New York, not UTC;
- previous-day data is never relabeled as today;
- overnight quotes are not expanded into fabricated bars.

Benign pre-open empty, closed market, and post-open provider anomaly are different states.

## China mutual-fund data

China fund NAV/open-day synchronization is separate from the tracked-US-ETF provider path.

The initial adapter uses EastMoney:

- fund historical NAV endpoint for six-digit China fund codes;
- Shanghai-market daily kline dates as confirmed historical open dates.

The synchronization service fetches the requested provider data before writing. Upstream failure must preserve existing local NAV/calendar facts.

A confirmed open date without NAV is a visible gap:

```text
open day + exact NAV -> eligible auto-DCA execution
open day + no NAV    -> gap; no synthetic purchase
closed day           -> no expected execution
```

Published fund NAV already reflects fund-level accrued management expenses; the application must not subtract annual management fee again.

The weekday fund refresh runs in `Asia/Shanghai` and re-fetches a recent rolling window so delayed NAV publication can be repaired.

## V025 USD/CNY FX

`fx_rate_daily` is a first-class fact table. Current convention:

```text
base  = USD
quote = CNY
rate  = CNY units per 1 USD
source = YAHOO:CNY=X
```

Example:

```text
USD/CNY 7.10 => 1 USD = 7.10 CNY
CNY 710 / 7.10 = USD 100
```

APIs:

```text
GET  /api/v1/fx/usd-cny?startDate=...&endDate=...
POST /api/v1/fx/usd-cny/sync?startDate=...&endDate=...
```

Reporting reads local `fx_rate_daily` only. It does not call Yahoo on a page read. Explicit/scheduled synchronization is the network boundary.

The V025 scheduler refreshes a recent USD/CNY window. A recent provider response with no usable rates is treated as a failure rather than evidence that FX does not exist. Existing rows are preserved on failure.

For reporting conversion, latest FX on/before target date may be used only within a seven-calendar-day carry window. Older data is treated as missing for combined reporting.

Yahoo `CNY=X` is a market reporting rate. It is not represented as an official PBOC fixing, card-network rate, or executable bank FX quote.

## Retry / fallback

US provider retry is bounded to retryable conditions such as timeout, HTTP 408/429/5xx, and relevant post-open anomalies.

Configured US fallback is used only when distinct and usable. `fallbackProvider=NONE` is authoritative.

China fund and V025 FX adapters are currently separate source paths and do not pretend to have a fallback that is not implemented.

## Freshness

Common states:

- `FRESH` — complete usable data within the relevant age/session rules;
- `STALE` — older value deliberately retained;
- `PARTIAL` — request valid but legitimately incomplete;
- `UNAVAILABLE` — no honest value can be produced;
- `INSUFFICIENT_HISTORY` — historical endpoints are insufficient.

Provider outage must not produce a fake fresh value.

For V025 combined reporting, required FX/NAV incompleteness produces `PARTIAL`; a fabricated converted value/live endpoint is forbidden.

## Benchmark data

Benchmark search/history remains isolated from tracked portfolio facts.

Types:

```text
ETF
INDEX
EQUITY
```

Yahoo benchmark history retains each benchmark's own market dates.

### US/default benchmark freshness

Uses `America/New_York` and completed 16:00 ET regular close.

### A-share benchmark freshness

`.SS` / `.SZ` history uses `Asia/Shanghai` and the 15:00 Shanghai regular-close boundary. Exchange-local trade dates are preserved. Current implementation includes explicit 2026 SSE closure handling used by tests.

Benchmark-specific calendar behavior does not change the real USD portfolio's New York business zone or the China-fund persisted calendar.

## Portfolio vs benchmark vs reporting

Three concepts should not be conflated:

1. real USD portfolio performance may append a complete FRESH live endpoint;
2. benchmark history remains regular-close and must not fabricate a live close;
3. V025 Reporting creates a separate USD-converted performance source from real USD + derived CNY + FX facts.

## Provider observability

Safe low-cardinality operational fields include provider, operation, outcome, status, latency, and retry attempt.

Never log:

- API keys;
- Yahoo cookie/crumb material;
- auth/session values;
- proxy credentials;
- database credentials;
- SQL;
- full user notes.

## Synchronization

Current scheduled paths are independent:

```text
US market sync       New York market zone
China fund sync      Asia/Shanghai
USD/CNY FX sync      recent daily reporting FX refresh
```

A scheduler failure should leave already-persisted facts intact and surface through logs/monitoring rather than deleting data.

## Repair rules

For material history repair:

1. take and verify a PostgreSQL backup;
2. record current row/date/control totals;
3. fetch before replacing/upserting;
4. verify source/date/value controls and dependent calculations;
5. restore from verified backup if validation fails.

Never clear history first, synthesize old portfolio values from current holdings, copy raw close into missing adjusted close, use NAV as price, or infer FX from unrelated instruments.

## Known reliability gaps

First-class operator views still need to cover:

- expected US trading-day and adjusted-close gaps;
- provider health history;
- EastMoney fund-source health/fallback;
- USD/CNY FX completeness/gap/source health;
- bounded observable repair queues;
- protected management metrics for long-running operations.

These are roadmap work, not existing guarantees.