# DCA Terminal v1 Market Data

## Provider boundary

Business code depends on a provider interface, not a named vendor service:

```java
interface MarketDataProvider {
    ProviderId id();
    boolean isConfigured();
    List<ProviderSearchResult> search(String query);
    ProviderQuote getLatestQuote(Instrument instrument);
    List<PriceBar> getHistoricalPrices(Instrument instrument,
                                       LocalDate from, LocalDate to);
    List<IntradayBar> getIntradayPrices(Instrument instrument,
                                        LocalDate from, LocalDate to);
    Optional<EtfProfile> getProfile(Instrument instrument);
    List<SplitEvent> getSplits(Instrument instrument,
                               LocalDate from, LocalDate to);
}
```

The registry resolves an ordered chain. The configured v1 default order is:

```text
Yahoo Finance -> Twelve Data (when configured) -> canonical identity catalog
                                                    -> unavailable/stale result
```

Alpha Vantage is an optional provider slot, not a high-frequency quote
dependency. Yahoo is an unofficial source and its availability and licensing
constraints must be treated as operational risks. Provider-specific response
parsing stays inside each adapter. Yahoo symbol search uses its
`/v6/finance/autocomplete` directory endpoint and accepts only results whose
provider type is `ETF`; the older `/v1/finance/search` endpoint is not used
because it is frequently rate-limited. Twelve Data is a live optional fallback
when its key is configured, while Alpha Vantage remains limited to its v1
profile capability.

Yahoo requests accept the optional `YAHOO_PROXY_URL` deployment setting. This
is useful when the API runs in a Docker network that cannot use the host's
egress directly; the proxy is configured only on the server-side provider
client and is never sent to the browser. An empty setting preserves direct
egress. The default Yahoo host is `query2.finance.yahoo.com`; deployments can
override it with `YAHOO_BASE_URL` if their egress has different Yahoo edge
availability.

An empty successful provider directory response means there are no matching
instruments and returns `200 []`. If every configured provider is unavailable,
the API uses the reviewed canonical identity catalog for known ETF matches.
For an unknown query it returns `503 MARKET_DATA_UNAVAILABLE` so the web UI
can distinguish an outage from a valid no-results search. The catalog contains
identity metadata only and cannot be used as a source for prices or history.

## Fallback and retry policy

For each request, the registry:

1. Calls the primary provider with a bounded connect/read timeout.
2. Retries only transient timeout, HTTP 429, and HTTP 5xx failures with a
   small bounded backoff.
3. Falls back to the next configured provider after the retry budget is
   exhausted.
4. Stops immediately for symbol-not-found, invalid-symbol, authentication, or
   schema errors; these are not transient failures.
5. Returns the newest locally stored value with a `STALE` state when a cached
   value exists, or `UNAVAILABLE` when no honest value exists.

The fallback result records its actual `source`; it is never labeled as the
primary provider. A provider failure is logged with provider, symbol,
operation, HTTP/error category, fallback target, and duration. No API key or
authorization header is logged.

## Data separation

Market price and fund NAV are different facts and are stored separately:

```text
market_price_daily(instrument_id, trade_date, open, high, low, close,
                   adjusted_close, volume, source)
fund_nav_daily(instrument_id, nav_date, nav, source, retrieved_at)
```

`market_price_daily.close` is the traded market close. `adjusted_close` is the
provider-adjusted historical close used for return and drawdown calculations
when available. NAV is the fund's calculated net asset value and may be absent
for a provider or date. The system must never store `nav = market price` as a
fallback. If NAV is unavailable, the UI displays `--` or hides that field.

Split events are stored in `instrument_split` with an effective date and
numeric numerator/denominator. A split changes the ledger's share quantity and
per-share cost when replaying dates after the event; it does not create a user
transaction or dividend.

## Storage and cache policy

| Data | Storage | Cache/retention |
| --- | --- | --- |
| Latest quote | `market_quote_latest` | Caffeine, 60 seconds; includes price, previous close, change, bid/ask, source, and freshness |
| 1D five-minute bars | Provider response | Caffeine, 60 seconds; no permanent intraday table in v1 |
| ETF profile | Instrument/profile columns | Caffeine, 24 hours |
| Daily OHLCV | `market_price_daily` | Permanent local cache, incrementally updated |
| NAV | `fund_nav_daily` | Permanent local cache, incrementally updated |
| Split events | `instrument_split` | Permanent local cache, incrementally updated |

When an ETF is first tracked, the API requests at least five years of daily
bars and stores the normalized result. Later jobs request only the range after
the last successful stored trade date through the current date. Upserts are
idempotent on `(instrument_id, trade_date, source)`.

The quote cache is process-local Caffeine. It is intentionally not Redis in
v1. A cache miss may call the configured provider chain; no permanent
intraday store is maintained.

## Synchronization

When enabled, the weekday scheduler runs at 18:30 in `America/New_York`, after
typical ETF data providers have published daily bars. It:

1. Loads active/tracked instruments.
2. Fetches missing daily prices and split events through the provider chain.
3. Upserts the normalized bars and split events.

ETF metrics are calculated on the metrics endpoint from the stored bars. The
scheduler does not fetch NAV/profile data, but it rebuilds the current
portfolio snapshot after the active-instrument sync batch.

The job is idempotent and safe to retry. A partial instrument batch does not
erase existing rows or mark unrelated instruments unavailable. The API remains
usable while a synchronization job is running.

## Normalization rules

- All provider timestamps are converted to UTC for storage; market date
  decisions use `America/New_York`.
- `trade_date` is the provider's actual trading date, never the retrieval date.
- Provider decimals are parsed into `BigDecimal`; binary floating-point values
  are not used for persistence or financial calculations.
- Daily rows require a valid date and close. High/low/open/volume may be
  nullable only when the provider contract permits it; a metric requiring a
  missing field returns a missing-data state.
- Duplicate provider rows are resolved deterministically, preferring the
  provider's final row and retaining the source identifier.
- Adjusted close remains nullable when unavailable. Performance metrics may use
  raw close only where the documented calculation explicitly permits it.
- A provider's dividend adjustment in `adjusted_close` is used only for
  historical performance. It is not converted into a user's DIVIDEND
  transaction and does not increase portfolio cash.

## Freshness states

`FRESH` means the requested value is available within the configured expected
market-data age. `STALE` means a prior value is shown past that age.
`PARTIAL` means some requested bars/instruments/fields are missing.
`UNAVAILABLE` means no value can be displayed honestly. A metric with too
little history may additionally carry `INSUFFICIENT_HISTORY`; it must return
null rather than extrapolating.

Quote responses expose `retrievedAt`, `source`, and `status`; metrics expose
`asOf` and `dataStatus`; portfolio summary exposes `asOf` and `dataStatus`.
Instrument identity responses also expose the persisted latest daily-history
status, so a newly confirmed ETF with a failed initial sync is visible as
degraded instead of looking complete. Other v1 arrays do not carry a universal
freshness envelope.
The web UI must show delayed data clearly, for example "Data delayed; last
update 2026-08-26 16:00 ET". A provider outage is a degraded-data state, not a
reason to fabricate a current price or return HTTP 500 for the entire
dashboard.

## Provider secrets and configuration

`TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` are read by the API process
from the deployment environment. They are not Vite variables, frontend
metadata, response fields, logs, GitHub Actions secrets, or tracked files.
Missing optional fallback keys reduce redundancy but do not prevent the API
from starting with Yahoo as the primary provider.

## Provider tests

Provider adapters are tested with recorded, sanitized fixtures or a mock HTTP
server. Tests must cover a successful response, malformed data, timeout, 429,
5xx, symbol-not-found, authentication failure, and fallback to the next
provider. No automated test may depend on live Yahoo, Twelve Data, or Alpha
Vantage network access.
