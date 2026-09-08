# China mutual-fund phase 2

Phase 2 makes the V023 China mutual-fund foundation operational without merging CNY assets into the USD portfolio engine yet.

## Source-of-truth boundaries

- `auto_dca_rule` remains the source fact for automatic fund DCA.
- `fund_nav_daily` stores observed unit NAV facts. A DCA execution is derived only when a NAV exists for that fund/date.
- `fund_market_calendar_day` stores confirmed China market-open dates. It is used for NAV gap audit and T+n confirmation timing.
- A market-open date without a fund NAV is reported as a missing-NAV gap. It never receives a carry-forward NAV and never creates a synthetic purchase.
- Management fee remains fund metadata only. Published NAV already reflects accrued fund expenses, so the application does not deduct that fee again.

## Provider and sync

The initial provider is EastMoney and is treated as an unofficial external data source, in the same fail-honestly spirit as the existing market-data providers.

Explicit sync:

`POST /api/v1/funds/{id}/sync?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD`

The server fetches both fund NAV history and confirmed Shanghai-market open dates before writing either dataset. Provider failure returns an upstream error and does not erase existing local facts.

Default explicit sync range is one year. The maximum single sync range is 5,500 days.

Automatic refresh runs at 22:30 Asia/Shanghai on weekdays by default and re-fetches the latest 14 days so delayed NAV publication can repair recent gaps.

Environment controls:

- `FUND_SYNC_ENABLED`
- `FUND_SYNC_CRON`
- `EASTMONEY_FUND_NAV_BASE_URL`
- `EASTMONEY_MARKET_BASE_URL`
- `EASTMONEY_TIMEOUT_MS`

## Calendar audit

`GET /api/v1/funds/{id}/calendar?startDate=...&endDate=...`

returns the confirmed open-day count, observed NAV-day count, and exact missing NAV dates. Closed dates are absent rather than guessed.

## DCA confirmation timing

When persisted China trading days exist, share confirmation uses those dates. If the requested historical range is not yet covered by the persisted calendar, confirmation timing falls back to observed NAV dates for uncovered rows and the projection response reports `PERSISTED_CN_TRADING_DAYS_WITH_NAV_FALLBACK`.

Actual DCA executions never use the fallback to manufacture a NAV. They still require an observed NAV row.

## Phase 2 UI

The fund workspace is intended to provide:

- fund create/edit;
- explicit NAV/calendar sync;
- latest NAV and gap visibility;
- automatic-DCA rule create/edit;
- month/year summaries;
- expandable daily derived executions.

## Still intentionally out of scope

- real broker/order execution;
- CNY cash ledger and manual fund investment transactions;
- FX conversion;
- merging CNY mutual funds into USD portfolio value, TWR or XIRR;
- investment advice or return prediction.
