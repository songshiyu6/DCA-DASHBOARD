# DCA Terminal Market Data

> Current baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`.

## Provider boundary

Business code depends on provider interfaces/registry rather than hard-coding one vendor throughout the domain. Current provider roles:

- Yahoo Finance: default live/search/history source and benchmark source;
- Twelve Data: optional configured fallback for supported market-data operations;
- Alpha Vantage: optional profile capability.

Provider keys remain server-side. `YAHOO_PROXY_URL` is also server-side.

## Data facts remain separate

The application must continue to distinguish:

```text
latest traded quote
regular-session daily market price
provider-adjusted close
fund NAV
split event
```

NAV is never replaced with market price. Adjusted close is used only where documented and is not a substitute for user dividend transactions.

## Current quote path

Current portfolio valuation prefers the newest valid timestamped quote candidate among regular, pre-market, post-market, extended, and overnight sessions.

The quote response still compares against the previous regular close. A current after-hours move can therefore change live account value and Today P/L while regular-close historical points remain unchanged.

If live quote retrieval fails, the service may retain a stored quote or daily close with degraded freshness. Missing prices must never be treated as zero.

## Daily history

`market_price_daily` stores regular-session OHLCV plus optional adjusted close and source. Historical portfolio replay uses raw regular close; ETF return/drawdown metrics use adjusted close where documented.

History is fetched incrementally and can be repaired by bounded full resync. Full resync fetches before persistence and never clears old rows first.

## 1D intraday

`GET /api/v1/instruments/{symbol}/prices?range=1D` is an on-demand provider path and is not persisted as a permanent five-minute store.

For Yahoo US ETF intraday normalization:

- date/session decisions use `America/New_York`;
- provider `currentTradingPeriod` boundaries are respected when usable;
- `[start,end)` session boundaries are used;
- invalid/missing Yahoo exchange timezone falls back to New York rather than UTC;
- a previous trading day is never relabeled as current-day data;
- an overnight quote is never expanded into synthetic bars.

A benign pre-open empty result, a closed market, and a post-open empty/anomalous provider result are distinct states.

## Retry / fallback

Retry is bounded and only for retryable conditions such as timeout, HTTP 408/429/5xx, and post-open intraday anomalies that no longer represent a benign empty session.

Configured fallback is used only when distinct and usable. `fallbackProvider=NONE` is authoritative.

A successful fallback is reported with its actual provider source.

## Freshness

Common states:

- `FRESH` — usable data within expected age/current-session rules;
- `STALE` — an older value is deliberately retained;
- `PARTIAL` — request is valid but legitimately incomplete;
- `UNAVAILABLE` — no honest value can be produced from configured sources;
- `INSUFFICIENT_HISTORY` — calculation lacks required historical endpoints.

Provider outage must not be converted into a fake fresh value.

## Benchmark data

Benchmark search/history is intentionally isolated from tracked instruments and portfolio facts.

Current benchmark types:

```text
ETF
INDEX
EQUITY
```

Yahoo benchmark search accepts the provider's compact/full type forms and routes China-style six-digit/`.SS`/`.SZ` queries to the CN search region where implemented.

Benchmark history preserves the benchmark's own trading dates rather than forcing every series through the US ETF calendar.

### US benchmark freshness

US/default benchmark history uses `America/New_York` and regular-close completion at 16:00 ET. Before the close, a current-day partial bar is not treated as a completed daily close.

### A-share benchmark freshness

Yahoo `.SS` / `.SZ` benchmark history uses `Asia/Shanghai` and the 15:00 Shanghai regular-close boundary. Shanghai calendar boundaries are used for Yahoo period requests and returned daily timestamps are mapped using exchange-local trade dates.

The current implementation includes explicit 2026 SSE closure handling used by benchmark freshness tests. This is benchmark-specific calendar logic and does not change the US ETF portfolio business zone.

## Benchmark refresh behavior

The Web invalidates/refetches benchmark history when the latest fresh portfolio regular-close date advances. Fresh visible benchmarks refresh periodically; stale benchmarks retry more frequently until they catch up.

This solves the case where a portfolio curve advances while a cached benchmark remains one close behind.

## Current portfolio vs benchmark performance

The portfolio performance engine can include a live FRESH current endpoint. Benchmark history remains regular-close data. UI comparison logic must label/rebase series honestly and must not fabricate a live benchmark close from intraday data.

## Provider observability

Logs/metrics may record low-cardinality operational facts such as provider, operation, outcome, status, latency, retry attempt, and safe intraday normalization counts.

Do not log:

- API keys;
- Yahoo cookie/crumb material;
- authorization headers;
- session/cookie values;
- proxy credentials;
- database credentials;
- SQL;
- full user notes.

## Synchronization

The weekday market-data scheduler remains tied to the New York market zone and rebuilds a regular-close portfolio snapshot after its instrument sync batch.

Current account valuation and performance can refresh independently of that scheduled snapshot through live quote/current-summary paths.

## Repair rules

For a full-history repair:

1. take and verify a PostgreSQL backup;
2. record current row/date/adjusted-close controls;
3. run the authenticated full-sync endpoint;
4. verify source, row counts, representative close/adjusted-close values, and dependent metrics;
5. restore from verified backup if validation fails.

Never clear history first, use current holdings to synthesize history, copy raw close into missing adjusted close, or use NAV as market price.

## Known reliability gaps

Current product still lacks first-class user/operator views for:

- tracked-instrument expected trading-day gap audit;
- adjusted-close completeness audit;
- provider health history;
- bounded observable repair queue;
- protected management metrics suitable for long-running operations.

These remain roadmap items rather than documented existing functionality.
