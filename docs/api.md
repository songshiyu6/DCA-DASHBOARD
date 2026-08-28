# DCA Terminal v1 API

This document describes the HTTP contract implemented by the current Spring
Boot application. It is intentionally limited to the endpoints and fields
that exist in v1; planned endpoints are not listed as if they were available.

## Base and wire conventions

The API is served by Caddy below `/api` and is versioned below `/api/v1`. The
production web application uses the same origin. A normal response is either
a JSON object or a bare JSON array; there is no universal `{ data, meta }`
envelope.

The API uses Jackson's default `BigDecimal` serialization. Monetary values,
prices, quantities, weights, and rates are therefore JSON numbers (or `null`),
not JSON strings. Clients that need arbitrary precision should convert them to
a decimal type at the boundary. Examples below use numbers for this reason.

Dates are ISO-8601 calendar dates. Timestamps are ISO-8601 UTC strings.
Symbols are case-insensitive on input and are returned in uppercase.

Freshness appears only on response types that currently define it. Depending
on the endpoint, the field is named `status` or `dataStatus`; it is not added
to every response automatically. The possible freshness values are:

`FRESH`, `STALE`, `PARTIAL`, `UNAVAILABLE`, and `INSUFFICIENT_HISTORY`.

`INSUFFICIENT_HISTORY` is used by the metrics response when the requested
calculation cannot be made from the stored bars. Missing NAV is `null`; the
market quote is never copied into the NAV field.

## Health and session

| Method | Path | Authentication | Response |
| --- | --- | --- | --- |
| `GET` | `/api/health` | Public | Minimal health object |
| `GET` | `/api/v1/auth/session` | Public | `{ authenticated, username }` |
| `GET` | `/api/v1/auth/csrf` | Public | `{ token, headerName, parameterName }` |
| `POST` | `/api/v1/auth/login` | Public | `{ authenticated, username }` |
| `POST` | `/api/v1/auth/logout` | Session | `204 No Content` |

With security enabled, all application endpoints other than health and the
session/login/CSRF endpoints require the single user's session cookie. POST,
PUT, and DELETE requests also require the CSRF token returned by the CSRF
endpoint, sent using the returned `headerName` (normally `X-XSRF-TOKEN`).

`GET /api/health` returns an object like:

```json
{
  "status": "UP",
  "service": "dca-terminal-api",
  "timestamp": "2026-08-27T20:00:12Z"
}
```

The internal `/actuator/health` endpoint is used by the API container
healthcheck. It is not one of the public API contract endpoints.

## Instruments and ETF data

| Method | Path | Response and behavior |
| --- | --- | --- |
| `GET` | `/api/v1/instruments` | Bare array of tracked ETF identity/profile objects |
| `GET` | `/api/v1/instruments/search?q=VOO` | Bare array of provider search candidates |
| `POST` | `/api/v1/instruments` | Creates or re-enables one tracked ETF; returns `201` and the instrument object |
| `GET` | `/api/v1/instruments/{symbol}` | One ETF identity/profile object |
| `DELETE` | `/api/v1/instruments/{symbol}` | Stops tracking; returns `204` and keeps historical references |
| `GET` | `/api/v1/instruments/{symbol}/quote` | Latest market quote, optional NAV, and quote freshness |
| `GET` | `/api/v1/instruments/{symbol}/metrics` | Metrics calculated from stored daily bars |
| `GET` | `/api/v1/instruments/{symbol}/prices?range=1Y` | `{ data, dataStatus, source, asOf, retrievedAt, message }` daily chart envelope |
| `GET` | `/api/v1/instruments/{symbol}/prices?range=1D` | Same envelope with on-demand five-minute provider bars |
| `POST` | `/api/v1/instruments/{symbol}/sync` | Fetches missing daily bars/splits and returns a sync summary |
| `POST` | `/api/v1/instruments/{symbol}/sync/full` | Re-fetches the bounded five-year history without clearing existing rows |
| `GET` | `/api/v1/instruments/providers` | Provider IDs, configured flags, primary, and fallback IDs |

`GET /api/v1/instruments` returns only instrument identity/profile fields. It
does not include the latest quote; the web client requests quotes separately.
Adding an ETF requires an exact ETF identity match from the configured
provider or the reviewed local canonical identity catalog, creates the
instrument, refreshes its profile, and immediately attempts the five-year
incremental history sync. The local catalog is an identity allowlist only; it
never supplies prices, history, NAV, or portfolio values. If the initial sync
is unavailable, the confirmed instrument is retained with dataStatus:
"UNAVAILABLE" (or INSUFFICIENT_HISTORY when the provider returns no bars) and
can be retried with the sync endpoint or scheduler.

When a provider directory is unavailable, a search for a canonical symbol may
still return its reviewed identity record. A search with no canonical match
returns `503 MARKET_DATA_UNAVAILABLE` rather than an incorrect empty success;
an ordinary provider response with no matches remains `200 []`.

The POST request uses `symbol`; `ticker` is accepted as a JSON alias:

```json
{ "symbol": "VOO" }
```

An instrument response has this shape:

```json
{
  "id": "00000000-0000-0000-0000-000000000001",
  "symbol": "VOO",
  "name": "Vanguard S&P 500 ETF",
  "exchange": "ARCX",
  "currency": "USD",
  "instrumentType": "ETF",
  "issuer": "Vanguard",
  "expenseRatio": 0.0003,
  "aum": 712400000000.0,
  "dividendYield": 0.0125,
  "dataProvider": "YAHOO",
  "tracked": true,
  "dataStatus": "FRESH"
}
```

Profile values may be `null`. Search results contain only `symbol`, `name`,
`exchange`, `currency`, and `instrumentType`; they do not contain an ID or
profile metrics.

Example quote response:

```json
{
  "symbol": "VOO",
  "price": 521.43,
  "previousClose": 519.25,
  "change": 2.18,
  "changePercent": 0.004195,
  "bid": null,
  "ask": null,
  "marketTimestamp": "2026-08-27T20:00:00Z",
  "retrievedAt": "2026-08-27T20:00:12Z",
  "source": "YAHOO",
  "status": "FRESH",
  "nav": null,
  "navDate": null
}
```

`changePercent` is a decimal fraction, so `0.004195` is approximately
`0.4195%`. Quote cache entries may be returned as `STALE` after a provider
failure. If there is no usable cached quote, `price` may be `null` and status
is `UNAVAILABLE`.

Metrics response:

```json
{
  "oneDay": 0.0042,
  "oneMonth": 0.021,
  "threeMonths": 0.0471,
  "ytd": 0.1234,
  "oneYear": 0.188,
  "threeYearCagr": 0.1421,
  "fiftyTwoWeekHigh": 551.90,
  "fiftyTwoWeekLow": 421.33,
  "currentDrawdown": -0.0552,
  "maxDrawdown1Y": -0.1823,
  "dataStatus": "FRESH",
  "asOf": "2026-08-27"
}
```

The daily prices endpoint returns an envelope whose `data` array contains
`date`, `close`, and `adjustedClose` plus `dataStatus`, `source`, `asOf`, and
`retrievedAt`. Daily dates are `YYYY-MM-DD`; the `1D` provider series uses UTC
timestamp strings and may have a null `adjustedClose`, so clients use `close`
for chart display in that case. Supported ranges are `1W`, `1M`, `3M`, `YTD`,
`1Y`, `3Y`, `5Y`, and `ALL`; the default is `1Y`. The `1D` series is not
persisted as a permanent intraday store. A failed request returns
`dataStatus: "UNAVAILABLE"`, never an empty-array `FRESH` response.

The sync response has this shape:

```json
{
  "symbol": "VOO",
  "barsSaved": 1258,
  "splitsSaved": 0,
  "status": "FRESH",
  "completedAt": "2026-08-27T20:02:00Z",
  "message": null
}
```

`POST /api/v1/instruments/{symbol}/sync/full` returns the same `SyncResponse`
shape. It is an explicit operator repair path with a hard five-year window and
the normal provider retry/fallback limits. It fetches bars and splits before
persistence, upserts by instrument/date/source, and never deletes existing
rows first. A provider failure retains the previous rows and reports a
degraded status. Operators should take and verify a PostgreSQL backup, record
pre/post row counts and representative raw/adjusted values, and use the
checked-in restore script if validation requires rollback.

Metrics that require adjusted-close endpoints (`oneMonth`, `threeMonths`,
`ytd`, `oneYear`, `threeYearCagr`, `currentDrawdown`, and `maxDrawdown1Y`) are
null when a required adjusted value is missing. With the current non-null JSON
serialization, those null properties may be omitted on the wire; the web client
normalizes an omitted or explicit null value to its missing display `--`, never
to `0%`. The response carries `dataStatus: "PARTIAL"` or
`"INSUFFICIENT_HISTORY"`. The 52-week high and low continue to use raw high and
low.

The current default live provider is Yahoo Finance. Twelve Data provides live
search, quote, daily/intraday history, profile, and split capabilities when its
server-side key is configured. Alpha Vantage provides the optional ETF profile
capability. A provider key is never sent to the browser.

Yahoo's chart edge may return HTTP 429 for browser-shaped User-Agent strings.
The adapter uses a minimal `Mozilla/5.0` User-Agent and keeps the bounded retry
and fallback policy above. A provider outage never creates placeholder bars:
the instrument remains visible with `UNAVAILABLE` or
`INSUFFICIENT_HISTORY`, the sync endpoint returns a status/message, and the
ETF detail view exposes a manual retry action.

## Transactions

| Method | Path | Response and behavior |
| --- | --- | --- |
| `GET` | `/api/v1/transactions` | Bare array, optionally filtered by `symbol`, `from`, and `to` |
| `GET` | `/api/v1/transactions/{id}` | One transaction |
| `POST` | `/api/v1/transactions` | Creates one transaction and returns `201` |
| `PUT` | `/api/v1/transactions/{id}` | Replaces one transaction |
| `DELETE` | `/api/v1/transactions/{id}` | Deletes one transaction and returns `204` |
| `POST` | `/api/v1/transactions/import/preview` | Multipart CSV preview |
| `POST` | `/api/v1/transactions/import/commit` | Commits a validated CSV row set |

The transaction list currently has no `page`, `size`, or pagination response.
The supported filters are inclusive `from`/`to` ISO dates and a
case-insensitive `symbol` filter. Results are ordered by trade date and the
server-assigned `ledgerOrder` field, then ID. `ledgerOrder` is returned in each
transaction response and is the deterministic tie-breaker for same-day FIFO
replay; JSON and CSV imports assign it in submission order.

The JSON request fields are:

```json
{
  "instrumentSymbol": "VOO",
  "planCycleId": null,
  "transactionType": "BUY",
  "tradeDate": "2026-08-01",
  "quantity": 1.238423,
  "unitPrice": 520.45,
  "amount": null,
  "fee": 0,
  "notes": "August DCA"
}
```

`instrumentSymbol` accepts the input alias `symbol`, and
`transactionType` accepts the input alias `type`. `currency` is not a request
field; v1 stores transactions as USD and returns `currency: "USD"`.

For `BUY` and `SELL`, `quantity` must be positive, `unitPrice` must be
non-negative, and `amount` must be omitted or `null`. For `DIVIDEND` and
`FEE`, `amount` is required and non-negative; quantity and unit price are not
used. `fee` is optional and defaults to zero. `planCycleId` is nullable so
unplanned transactions are supported. The service validates the resulting
ledger after create, update, and delete, including negative split-adjusted
positions.

`tradeDate` must be on or before the current date in the configured application
timezone. JSON create/update requests with a future date return HTTP 400 with
the stable Problem Details code `FUTURE_TRADE_DATE_NOT_ALLOWED`; no transaction
is persisted. CSV rows with a future date are invalid and the whole CSV commit
is rejected.

Responses add the persisted fields `id`, `instrumentName`, `currency`,
`createdAt`, and `updatedAt`:

```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "instrumentSymbol": "VOO",
  "instrumentName": "Vanguard S&P 500 ETF",
  "transactionType": "BUY",
  "tradeDate": "2026-08-01",
  "quantity": 1.238423,
  "unitPrice": 520.45,
  "amount": null,
  "fee": 0,
  "currency": "USD",
  "planCycleId": null,
  "notes": "August DCA",
  "createdAt": "2026-08-27T20:03:00Z",
  "updatedAt": "2026-08-27T20:03:00Z"
}
```

### CSV import

The preview endpoint expects a multipart field named `file`. The required
header columns are:

```text
date,type,symbol,quantity,price,fee
2026-01-05,BUY,VOO,1.2034,415.21,0
2026-02-05,BUY,QQQ,0.8231,505.42,0
```

Optional columns are `amount`, `planCycleId` (or `plan_cycle_id`), and `notes`.
Preview returns
`batchId`, row counts, and a row-by-row validation result. Commit accepts the
`batchId` and the CSV rows as JSON:

```json
{
  "batchId": "00000000-0000-0000-0000-000000000003",
  "rows": [
    {
      "date": "2026-01-05",
      "type": "BUY",
      "symbol": "VOO",
      "quantity": "1.2034",
      "price": "415.21",
      "fee": "0",
      "amount": null,
      "planCycleId": null,
      "notes": "January DCA"
    }
  ]
}
```

The response contains `batchId`, `importedRows`, and `transactionIds`. A
duplicate or invalid row causes the commit to fail as a whole. When
`planCycleId` is provided, the server validates the UUID and confirms that the
cycle contains the transaction's instrument.

## Portfolio and dashboard

| Method | Path | Response and behavior |
| --- | --- | --- |
| `GET` | `/api/v1/dashboard` | Dashboard object containing summary, holdings, allocation, next DCA, and progress |
| `GET` | `/api/v1/portfolio/summary` | Summary object |
| `GET` | `/api/v1/portfolio/holdings` | Bare array of current holdings |
| `GET` | `/api/v1/portfolio/allocation` | Bare array of allocation rows |
| `GET` | `/api/v1/portfolio/history?range=1Y` | Bare array of daily history points |
| `POST` | `/api/v1/portfolio/rebuild-snapshot` | Rebuilds today's snapshot and returns an empty `200` response |

Portfolio state is calculated from transactions, split events, and prices.
There is no holdings mutation endpoint and no `POST /portfolio/update-holdings`.
Historical points include only transactions dated on or before the point's
date; current holdings are not backfilled before their purchase date.

Summary response:

```json
{
  "marketValue": 28421.62,
  "costBasis": 25180.39,
  "netInvested": 25180.39,
  "unrealizedPnl": 3241.23,
  "realizedPnl": 0,
  "dividendIncome": 0,
  "totalFees": 0,
  "totalPnl": 3241.23,
  "xirr": 0.1421,
  "dataStatus": "FRESH",
  "asOf": "2026-08-27T20:03:00Z"
}
```

Holding rows contain `symbol`, `name`, `price`, `todayPercent`, `shares`,
`avgCost`, `costBasis`, `marketValue`, `unrealizedPnl`, `returnPercent`,
`allocation`, and `dataStatus`.
History rows contain `date`, `marketValue`, `netInvested`, `costBasis`,
`unrealizedPnl`, and `status`. `marketValue` and `unrealizedPnl` are `null`
when `status` is `PARTIAL` because one or more held instruments have no usable
price for that date; `costBasis` and `netInvested` remain populated. Missing
market value is never encoded as zero. A history response is a bare array with
this shape:

```json
[
  {
    "date": "2026-08-26",
    "marketValue": null,
    "netInvested": 25180.39,
    "costBasis": 25180.39,
    "unrealizedPnl": null,
    "status": "PARTIAL"
  }
]
```

Allocation rows contain `symbol`, `targetWeight`, `actualWeight`, `drift`, and
`marketValue`.

The dashboard response shape is:

```json
{
  "summary": {
    "marketValue": 28421.62,
    "costBasis": 25180.39,
    "netInvested": 25180.39,
    "unrealizedPnl": 3241.23,
    "realizedPnl": 0,
    "dividendIncome": 0,
    "totalFees": 0,
    "totalPnl": 3241.23,
    "xirr": 0.1421,
    "dataStatus": "FRESH",
    "asOf": "2026-08-27T20:03:00Z"
  },
  "nextDca": null,
  "portfolioHistory": [],
  "holdings": [],
  "allocation": [],
  "contributionProgress": null
}
```

`nextDca` is a `NextDcaResponse` and `contributionProgress` is a
`ContributionProgress` object when an active plan exists. Both are `null` when
no active plan exists. `portfolioHistory` is a bare array of history points.
Holdings and allocation rows use the complete fields listed above, even when
an example has no rows.

## Plans and cycles

| Method | Path | Response and behavior |
| --- | --- | --- |
| `GET` | `/api/v1/plans` | Bare array of plan objects |
| `POST` | `/api/v1/plans` | Creates a plan and returns `201` |
| `GET` | `/api/v1/plans/{id}` | One plan object |
| `PUT` | `/api/v1/plans/{id}` | Updates a plan and future cycles |
| `POST` | `/api/v1/plans/{id}/archive` | Archives a plan and returns `204` |
| `DELETE` | `/api/v1/plans/{id}` | Archive alias; returns `204` |
| `GET` | `/api/v1/plans/{id}/cycles` | Bare array of monthly cycle objects |
| `GET` | `/api/v1/plans/{id}/cycles/{period}` | One cycle, where `period` is `YYYY-MM` |
| `GET` | `/api/v1/plans/{id}/progress` | Current-year contribution progress object |
| `GET` | `/api/v1/plans/{id}/recommendation` | Contribution-first recommendation |

The plan request is:

```json
{
  "name": "Core ETF Plan",
  "frequency": "MONTHLY",
  "monthlyBudget": 1500,
  "startDate": "2026-01-01",
  "executionStartDay": 1,
  "executionEndDay": 7,
  "status": "ACTIVE",
  "assets": [
    { "symbol": "VOO", "targetWeight": 0.5 },
    { "symbol": "QQQ", "targetWeight": 0.3 },
    { "symbol": "SCHD", "targetWeight": 0.2 }
  ]
}
```

`frequency` may be omitted and defaults to monthly; non-monthly values are
rejected by the current v1 service. Execution days default to `1` and `7`.
Currency is not a request field; the current service stores plans as USD.
Asset symbols must already exist as instruments, must not repeat, and their
weights must sum to `1.0` within `0.0001`.

The plan response contains `id`, `name`, `currency`, `frequency`,
`monthlyBudget`, `startDate`, `executionStartDay`, `executionEndDay`, `status`,
`assets`, `createdAt`, and `updatedAt`. Each plan asset contains `symbol`,
`name`, `targetWeight`, and `plannedAmount`. The plan response does not embed
current progress; use the progress endpoint.

Cycle rows contain `id`, `planId`, `period`, `plannedAmount`, `executedAmount`,
`status`, `assets`, `openedAt`, and `completedAt`. Cycle asset rows contain
`symbol`, `targetWeight`, `plannedAmount`, and `executedAmount`.

The progress response contains `planned`, `executed`, `remaining`,
`executionRate`, and `months`, plus the current `year`. Each month contains
`period`, `planned`, `executed`, and `status`. The recommendation endpoint
accepts an optional `amount` query parameter and returns:

```json
{
  "amount": 1500,
  "dataStatus": "FRESH",
  "items": [
    {
      "symbol": "VOO",
      "currentWeight": 0.54,
      "targetWeight": 0.5,
      "currentValue": 55000,
      "gap": -0.04,
      "suggestedAmount": 0,
      "positiveGap": 0,
      "reason": "OVERWEIGHT",
      "valueGap": -4250
    }
  ]
}
```

Recommendation amounts are rounded to cents. Overweight assets receive zero;
the remaining contribution is distributed across positive gaps. If a plan
asset has no available current price, response status is `PARTIAL` and the
suggestions are zero. If there are no positive gaps, the current service falls
back to the plan target weights for allocation.

## Settings

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/api/v1/settings` | Current non-secret application/provider settings |
| `PUT` | `/api/v1/settings` | Updates provider selection, theme, and timezone |

The settings response is:

```json
{
  "baseCurrency": "USD",
  "primaryProvider": "YAHOO",
  "fallbackProvider": "TWELVE_DATA",
  "twelveDataConfigured": false,
  "alphaVantageConfigured": false,
  "theme": "SYSTEM",
  "timezone": "America/New_York"
}
```

The PUT request accepts any subset of these fields:

```json
{
  "primaryProvider": "YAHOO",
  "fallbackProvider": "NONE",
  "theme": "DARK",
  "timezone": "America/New_York"
}
```

Provider values are `YAHOO`, `TWELVE_DATA`, `ALPHA_VANTAGE`, or `NONE` for
the fallback. Themes are `SYSTEM`, `LIGHT`, and `DARK`. Timezones must be
recognized Java zone IDs. Base currency and provider keys are not updated by
this endpoint. Provider keys and password hashes are never returned.

## Errors

Application validation and domain failures use Spring Problem Details with
the additional `code`, `timestamp`, and `path` properties. Validation errors
also include a `fields` object:

```json
{
  "type": "https://dca-terminal.invalid/problems/validation_error",
  "title": "VALIDATION_ERROR",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/plans",
  "code": "VALIDATION_ERROR",
  "timestamp": "2026-08-27T20:04:00Z",
  "path": "/api/v1/plans",
  "fields": {
    "name": "must not be blank"
  }
}
```

The server does not expose exception messages, credentials, provider keys, or
SQL details in unexpected-error responses.
