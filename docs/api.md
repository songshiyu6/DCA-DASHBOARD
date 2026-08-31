# DCA Terminal API

This document describes the HTTP contract implemented by the current Spring
Boot application. It is intentionally limited to endpoints and fields present
on the current `main`; roadmap items are not listed as available behavior.

## Base and wire conventions

The API is served by Caddy below `/api` and is versioned below `/api/v1`. The
production web application uses the same origin. A normal response is either
a JSON object or a bare JSON array; there is no universal `{ data, meta }`
envelope.

The API registers a global Jackson `BigDecimal` serializer. Monetary values,
prices, quantities, weights, and rates are plain decimal JSON strings when
present. Counts, calendar-day values, HTTP statuses, and ledger-order values are
JSON numbers. Global response serialization omits properties whose value is
null; request bodies may still send an explicit null where the contract allows
it. BigDecimal request fields accept either JSON numbers or decimal strings, and
the web client sends strings. API and Web regressions cover the full
`NUMERIC(20,6)` and `NUMERIC(20,8)` boundaries without an intermediate
JavaScript number. Examples below follow the actual non-null response format.

Dates are ISO-8601 calendar dates. Timestamps are ISO-8601 UTC strings.
Symbols are case-insensitive on input and are returned in uppercase.

Freshness appears only on response types that currently define it. Depending
on the endpoint, the field is named `status` or `dataStatus`; it is not added
to every response automatically. The possible freshness values are:

`FRESH`, `STALE`, `PARTIAL`, `UNAVAILABLE`, and `INSUFFICIENT_HISTORY`.

`INSUFFICIENT_HISTORY` is used by the metrics response when the requested
calculation cannot be made from the stored bars. Missing NAV fields are omitted;
the market quote is never copied into them.

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
Sessions are persisted in the PostgreSQL `SPRING_SESSION` tables. They normally
survive an API container restart, but logout deletes the server-side session
and clears the CSRF token.

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
  "expenseRatio": "0.0003",
  "aum": "712400000000.000000",
  "dividendYield": "0.0125",
  "dataProvider": "YAHOO",
  "tracked": true,
  "dataStatus": "FRESH"
}
```

Unavailable profile values are omitted. Search results contain only `symbol`,
`name`, `exchange`, `currency`, and `instrumentType`; they do not contain an ID
or profile metrics.

Example quote response:

```json
{
  "symbol": "VOO",
  "price": "521.430000",
  "previousClose": "519.250000",
  "change": "2.180000",
  "changePercent": "0.004195",
  "marketTimestamp": "2026-08-27T20:00:00Z",
  "retrievedAt": "2026-08-27T20:00:12Z",
  "source": "YAHOO",
  "status": "FRESH"
}
```

`changePercent` is a decimal fraction, so `0.004195` is approximately
`0.4195%`. Quote cache entries may be returned as `STALE` after a provider
failure. If there is no usable cached quote, `price` is omitted and status is
`UNAVAILABLE`.

Metrics response:

```json
{
  "oneDay": "0.0042",
  "oneMonth": "0.021",
  "threeMonths": "0.0471",
  "ytd": "0.1234",
  "oneYear": "0.188",
  "threeYearCagr": "0.1421",
  "fiftyTwoWeekHigh": "551.900000",
  "fiftyTwoWeekLow": "421.330000",
  "currentDrawdown": "-0.0552",
  "maxDrawdown1Y": "-0.1823",
  "dataStatus": "FRESH",
  "asOf": "2026-08-27"
}
```

The daily prices endpoint returns an envelope whose `data` array contains
`date`, `close`, and `adjustedClose` plus `dataStatus`, `source`, `asOf`, and
`retrievedAt`. Daily dates are `YYYY-MM-DD`; the `1D` provider series uses UTC
timestamp strings and may omit `adjustedClose`, so clients use `close` for chart
display in that case. Supported ranges are `1W`, `1M`, `3M`, `YTD`,
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
  "completedAt": "2026-08-27T20:02:00Z"
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
unset when a required adjusted value is missing. Those properties are omitted
on the wire; the web client
normalizes an omitted or explicit null value to its missing display `--`, never
to `0%`. The response carries `dataStatus: "PARTIAL"` or
`"INSUFFICIENT_HISTORY"`. The 52-week high and low continue to use raw high and
low.

The current default live provider is Yahoo Finance. Twelve Data provides live
search, quote, daily/intraday history, profile, and split capabilities when its
server-side key is configured. Alpha Vantage provides the optional ETF profile
capability. A provider key is never sent to the browser.

Yahoo latest-quote selection uses the newest valid timestamped candidate among
regular, pre-market, extended, post-market, and overnight prices. Current
portfolio and contribution valuation may therefore move outside regular hours.
The quote's comparison baseline remains the previous regular close; historical
snapshots, charts, YTD, and TWR continue to use regular-session daily closes.
If Yahoo's authenticated quote edge is unavailable, the adapter falls back to
the regular-session chart quote.

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
replay. The order is allocated by a PostgreSQL sequence, so concurrent API
instances cannot select the same next order; sequence gaps after a rolled-back
request are expected and do not change the order of existing rows. JSON and CSV
rows receive orders in the order they are inserted.

The JSON request fields are:

```json
{
  "instrumentSymbol": "VOO",
  "planCycleId": "00000000-0000-0000-0000-000000000011",
  "transactionType": "BUY",
  "tradeDate": "2026-08-01",
  "quantity": "1.238423",
  "unitPrice": "520.45",
  "fee": "0",
  "contributionType": "DCA",
  "notes": "August DCA"
}
```

`instrumentSymbol` accepts the input alias `symbol`, and
`transactionType` accepts the input alias `type`. `currency` is not a request
field; the current API stores transactions as USD and returns `currency: "USD"`.

For a BUY, `contributionType` may be `INITIAL`, `DCA`, `UNPLANNED`, or `null`.
A selected `planCycleId` forces `DCA`; the plan is inferred from the cycle and
`contributionPlanId` must be omitted. `INITIAL` requires
`contributionPlanId` and the BUY date must equal that plan's `startDate`.
`UNPLANNED` must not carry a plan ID. SELL, DIVIDEND, and FEE reject all
contribution source fields. A nullable contribution source is retained for
legacy/unclassified BUY rows so the UI can ask the user to classify them
explicitly instead of guessing. A database upgraded from before V016 can also
contain a cycle-linked BUY whose `contributionType` is omitted; plan projections
and the web UI still identify it as DCA from `planCycleId`, while a new or edited
API write persists the explicit `DCA` type.

For `BUY` and `SELL`, `quantity` must be positive, `unitPrice` must be
non-negative, and `amount` must be omitted or `null`. For `DIVIDEND` and
`FEE`, `amount` is required and non-negative; quantity and unit price are not
used. `fee` is optional and defaults to zero. `planCycleId` is nullable so
unplanned transactions are supported. The service validates the resulting
ledger after create, update, and delete, including negative split-adjusted
positions.

`tradeDate` must be on or before the current date in the fixed
`America/New_York` business zone. JSON create/update requests with a future
date return HTTP 400 with the stable Problem Details code
`FUTURE_TRADE_DATE_NOT_ALLOWED`; no transaction is persisted. CSV rows with a
future date are invalid and the whole CSV commit is rejected.

Responses add the persisted fields `id`, `instrumentName`, `currency`,
`createdAt`, and `updatedAt`:

```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "instrumentSymbol": "VOO",
  "instrumentName": "Vanguard S&P 500 ETF",
  "transactionType": "BUY",
  "tradeDate": "2026-08-01",
  "quantity": "1.23842300",
  "unitPrice": "520.450000",
  "fee": "0.000000",
  "currency": "USD",
  "planCycleId": "00000000-0000-0000-0000-000000000011",
  "contributionType": "DCA",
  "notes": "August DCA",
  "createdAt": "2026-08-27T20:03:00Z",
  "updatedAt": "2026-08-27T20:03:00Z",
  "ledgerOrder": 42
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
cycle contains the transaction's instrument. Commit validation errors identify
the 1-based CSV line number (the header is line 1), so a duplicate row is
reported with its exact input row. Preview and commit use the same canonical
row fingerprint; the database also enforces the global fingerprint uniqueness
constraint.

CSV uploads are bounded before import: the default multipart file limit is
1 MiB, the default multipart request limit is 2 MiB, the default maximum is
10,000 data rows, and each field is limited to 1,000 characters. These limits can be changed with
`TRANSACTION_MAX_CSV_SIZE`, `TRANSACTION_MAX_CSV_REQUEST_SIZE`,
`TRANSACTION_MAX_CSV_ROWS`, and `TRANSACTION_MAX_CSV_FIELD_LENGTH`. Exceeding
the file or row limit returns HTTP 413 with `CSV_FILE_TOO_LARGE` or
`CSV_TOO_MANY_ROWS`; an overlong field is reported as a preview row validation
error and causes the whole commit to fail.
The service never logs a complete CSV row or the contents of `notes`.
CSV currently has no contribution-type column. A BUY with `planCycleId` is
classified as `DCA`; an unlinked imported BUY remains unclassified until it is
edited or explicitly marked as initial capital. Import never guesses that an
opening-date BUY is `INITIAL`.

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
`GET /api/v1/dashboard` builds current summary, holdings, and allocation from
one request-local ledger projection; history still uses independent snapshot
coverage plus dated replay. The JSON shape is unchanged. Individual
`/portfolio/summary`, `/holdings`, and `/allocation` endpoints still compute
independently. Historical points include only transactions dated on or before
the point's date; current holdings are not backfilled before their purchase
date.

Summary response:

```json
{
  "marketValue": "28421.620000",
  "costBasis": "25180.390000",
  "netInvested": "25180.390000",
  "unrealizedPnl": "3241.230000",
  "realizedPnl": "0.000000",
  "dividendIncome": "0.000000",
  "totalFees": "0.000000",
  "totalPnl": "3241.230000",
  "xirr": "0.1421",
  "dataStatus": "FRESH",
  "asOf": "2026-08-27T20:03:00Z"
}
```

Holding rows contain `symbol`, `name`, `price`, `todayPercent`, `shares`,
`avgCost`, `costBasis`, `marketValue`, `unrealizedPnl`, `returnPercent`,
`allocation`, and `dataStatus`.
History rows contain `date`, `marketValue`, `netInvested`, `costBasis`,
`unrealizedPnl`, and `status`. `marketValue` and `unrealizedPnl` are omitted
when `status` is `PARTIAL` because one or more held instruments have no usable
price for that date; `costBasis` and `netInvested` remain populated. Missing
market value is never encoded as zero. A history response is a bare array with
this shape:

```json
[
  {
    "date": "2026-08-26",
    "netInvested": "25180.390000",
    "costBasis": "25180.390000",
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
    "marketValue": "28421.620000",
    "costBasis": "25180.390000",
    "netInvested": "25180.390000",
    "unrealizedPnl": "3241.230000",
    "realizedPnl": "0.000000",
    "dividendIncome": "0.000000",
    "totalFees": "0.000000",
    "totalPnl": "3241.230000",
    "xirr": "0.1421",
    "dataStatus": "FRESH",
    "asOf": "2026-08-27T20:03:00Z"
  },
  "portfolioHistory": [],
  "holdings": [],
  "allocation": []
}
```

`nextDca` is a `NextDcaResponse` and `contributionProgress` is a
`ContributionProgress` object when an active plan exists. Both fields are
omitted when no active plan exists. `portfolioHistory` is a bare array of
history points. Holdings and allocation rows use the complete fields listed
above, even when an example has no rows.

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
| `GET` | `/api/v1/plans/{id}/contribution-analysis` | Initial-versus-DCA contribution buckets and batches |
| `POST` | `/api/v1/plans/{id}/contribution-classifications/preview` | Validates a selected legacy-BUY classification set without writing |
| `POST` | `/api/v1/plans/{id}/contribution-classifications/commit` | Atomically commits the exact valid preview hash and returns updated analysis |
| `GET` | `/api/v1/plans/{id}/contribution-classifications/audit` | Latest 100 confirmed classification audit rows for the plan |

The plan request is:

```json
{
  "name": "Core ETF Plan",
  "frequency": "MONTHLY",
  "monthlyBudget": "1500",
  "startDate": "2026-01-01",
  "executionStartDay": 1,
  "executionEndDay": 7,
  "status": "ACTIVE",
  "assets": [
    { "symbol": "VOO", "targetWeight": "0.5" },
    { "symbol": "QQQ", "targetWeight": "0.3" },
    { "symbol": "SCHD", "targetWeight": "0.2" }
  ]
}
```

`frequency` may be omitted and defaults to monthly; non-monthly values are
rejected by the current service. Execution days default to `1` and `7`.
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
If a plan month contains an actual `INITIAL` BUY for that plan and has no DCA
execution, the month is presented as a zero-budget `SKIPPED` DCA cycle. Linking
a DCA BUY to that month is rejected with `INITIAL_CAPITAL_MONTH_SKIPS_DCA`.
Without an actual initial-capital transaction, the start month behaves like a
normal DCA month.

The progress response contains `planned`, `executed`, `remaining`,
`executionRate`, and `months`, plus the current `year`. Each month contains
`period`, `planned`, `executed`, and `status`. The recommendation endpoint
accepts an optional `amount` query parameter and returns:

```json
{
  "amount": "1500.00",
  "dataStatus": "FRESH",
  "items": [
    {
      "symbol": "VOO",
      "currentWeight": "0.54",
      "targetWeight": "0.5",
      "currentValue": "55000.000000",
      "gap": "-0.04",
      "suggestedAmount": "0.00",
      "positiveGap": "0",
      "reason": "OVERWEIGHT",
      "valueGap": "-4250.000000"
    }
  ]
}
```

Recommendation amounts are rounded to cents. Overweight assets receive zero;
the remaining contribution is distributed across positive gaps. If a plan
asset has no available current price, response status is `PARTIAL` and the
suggestions are zero. If there are no positive gaps, the current service falls
back to the plan target weights for allocation.

## Contribution analysis

Contribution buckets and batches are a plan-scoped projection over the
transaction ledger; they are not a second cash ledger. The unclassified queue
is account-wide because those BUY rows have no plan attribution. An example
response is:

```json
{
  "totalInvested": "52000.000000",
  "initial": {
    "principal": "50000.000000",
    "value": "53850.000000",
    "pnl": "3850.000000",
    "returnRate": "0.077",
    "averageMarketDays": 92,
    "batchCount": 1,
    "dataStatus": "FRESH"
  },
  "dca": {
    "principal": "2000.000000",
    "value": "2106.000000",
    "pnl": "106.000000",
    "returnRate": "0.053",
    "averageMarketDays": 59,
    "batchCount": 2,
    "dataStatus": "FRESH"
  },
  "unclassifiedAmount": "800.000000",
  "unclassifiedBuys": [
    {
      "transactionId": "00000000-0000-0000-0000-000000000020",
      "tradeDate": "2026-01-01",
      "symbol": "VOO",
      "principal": "800.000000",
      "eligibleForInitial": true
    }
  ],
  "unclassifiedScope": "ACCOUNT",
  "batches": [
    {
      "type": "INITIAL",
      "principal": "50000.000000",
      "value": "53850.000000",
      "pnl": "3850.000000",
      "returnRate": "0.077",
      "averageMarketDays": 92,
      "dataStatus": "FRESH"
    }
  ],
  "dataStatus": "FRESH",
  "asOf": "2026-08-31"
}
```

`principal` is actual BUY cost including execution fee. `INITIAL` batches come
only from transactions explicitly linked to the requested plan; `DCA` batches
come from BUY transactions linked to that plan's cycles and are grouped by
cycle period. `UNPLANNED` and unclassified BUYs are not included in
`totalInvested`. Sells consume attributed lots in global FIFO order. Dividends
and standalone fees are currently excluded from contribution-batch P/L.
Returns are cumulative ROI, not annualized. Missing current prices omit the
affected value/P&L/return fields and degrade `dataStatus` rather than inventing
a value. `unclassifiedScope` is always `ACCOUNT`: the queue is returned for any
plan because those BUY rows have no plan attribution, but it is never assigned
to the current plan automatically.

The old nullable `investment_plan.initial_capital` column is retained only for
database compatibility. It is not mapped, returned, or writable by the current
application. Actual initial capital always comes from `INITIAL` BUY facts.

Legacy classification is explicitly two-phase. Preview accepts:

```json
{
  "items": [
    {
      "transactionId": "00000000-0000-0000-0000-000000000020",
      "classification": "INITIAL"
    },
    {
      "transactionId": "00000000-0000-0000-0000-000000000021",
      "classification": "UNPLANNED"
    }
  ]
}
```

The response contains `valid`, a `previewHash` only when every row is valid,
and per-row `errors`. Commit sends the same `items` plus `previewHash`. The
service locks the selected rows, recomputes the preview, rejects stale hashes,
and writes all transaction changes and audit rows in one database transaction.
Only an opening-date BUY may become `INITIAL`; `UNPLANNED` has no plan link.

## Settings

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/api/v1/settings` | Current non-secret application/provider settings |
| `PUT` | `/api/v1/settings` | Updates provider selection and theme |

The settings response is:

```json
{
  "baseCurrency": "USD",
  "primaryProvider": "YAHOO",
  "fallbackProvider": "TWELVE_DATA",
  "twelveDataConfigured": false,
  "alphaVantageConfigured": false,
  "theme": "SYSTEM"
}
```

The PUT request accepts any subset of these fields:

```json
{
  "primaryProvider": "YAHOO",
  "fallbackProvider": "NONE",
  "theme": "DARK"
}
```

Provider values are `YAHOO`, `TWELVE_DATA`, `ALPHA_VANTAGE`, or `NONE` for
the fallback. Themes are `SYSTEM`, `LIGHT`, and `DARK`. Base currency,
business timezone, and provider keys are not updated by this endpoint. The
business timezone is fixed to `America/New_York`; provider keys and password
hashes are never returned.

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

Current contribution-specific domain codes are:

| HTTP | Code | Meaning |
| --- | --- | --- |
| `400` | `CONTRIBUTION_SOURCE_REQUIRES_BUY` | A non-BUY request supplied contribution fields |
| `409` | `CONTRIBUTION_SOURCE_CONFLICT` | A cycle-linked BUY supplied a non-DCA contribution type |
| `400` | `DCA_CONTRIBUTION_REQUIRES_CYCLE` | A DCA BUY has no plan cycle |
| `400` | `INITIAL_CONTRIBUTION_REQUIRES_PLAN` | An initial BUY has no plan attribution |
| `400` | `INITIAL_CONTRIBUTION_START_DATE_ONLY` | The initial BUY date is not the selected plan's start date |
| `400` | `INVALID_CONTRIBUTION_PLAN` | Contribution type, plan, and cycle fields are inconsistent |
| `400` | `INITIAL_CAPITAL_MONTH_SKIPS_DCA` | A DCA BUY targets a month already used for actual initial capital |
| `400` | `CONTRIBUTION_CLASSIFICATION_EMPTY` | Preview or commit contains no selected transactions |
| `409` | `CONTRIBUTION_CLASSIFICATION_INVALID` | One or more rows are no longer eligible at commit time |
| `409` | `CONTRIBUTION_PREVIEW_STALE` | Commit hash or selected transaction state differs from the preview |

Preview row errors additionally use `TRANSACTION_NOT_FOUND`,
`DUPLICATE_TRANSACTION`, `UNSUPPORTED_CONTRIBUTION_CLASSIFICATION`,
`CONTRIBUTION_SOURCE_REQUIRES_BUY`, `DCA_CONTRIBUTION_ALREADY_CLASSIFIED`,
`CONTRIBUTION_ALREADY_CLASSIFIED`, and
`INITIAL_CONTRIBUTION_START_DATE_ONLY`. Invalid preview rows do not write data.
