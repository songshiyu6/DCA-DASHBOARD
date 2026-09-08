# DCA Terminal Current Agent Handoff

> Current feature state: V025 multi-currency USD reporting projection.
>
> Current Flyway chain: `V001`–`V025`.
>
> Next priorities: [`next-development-plan.md`](./next-development-plan.md)

This is the technical takeover entry point. Dated `sa-*.md` files are historical evidence only. When anything conflicts, use current source/migrations/tests/exact runtime or CI evidence first.

## 1. Current conclusion

DCA Terminal now has three deliberately separate accounting/reporting layers:

```text
1. real USD transaction/cash account
2. derived CNY China-fund automatic-DCA subportfolio
3. V025 USD reporting projection that combines 1 + 2 using historical USD/CNY
```

Do not collapse these layers.

The real ledger is still USD-only. A CNY fund position shown in Reporting is not a real transaction, cash balance, or broker holding.

## 2. Source priority

```text
current source + published migration
> current tests
> current runtime / exact CI evidence
> living docs
> dated sa-*.md reports
```

Never rewrite historical SA evidence to look current.

## 3. Current Web surface

| Workspace | Meaning |
| --- | --- |
| Dashboard | real USD account total/cash/securities, Today, allocation, plan progress, canonical USD performance |
| Reporting | V025 USD reporting projection over real USD + derived CNY fund subportfolio + historical FX |
| Plan | one active monthly USD plan, frozen cycles, execution status, recommendations |
| Contributions | real INITIAL/DCA BUY-lot attribution, classification workflow/audit |
| ETFs | tracked US ETF quote/history/metrics/sync/freshness |
| China Funds | fund metadata, EastMoney NAV/open-day sync, gap audit, auto-DCA rules and summaries |
| Transactions | real USD DEPOSIT/WITHDRAWAL/INTEREST/BUY/SELL/DIVIDEND/FEE + CSV |
| Settings | theme/provider configuration state |

Reporting is hidden in Demo mode because Demo must not invent FX/CNY facts.

## 4. Non-negotiable real-ledger rules

There is no editable holdings or cash state.

```text
transactions + splits + prices -> FIFO holdings / cash / real USD portfolio / USD performance
```

Real USD cash change:

```text
DEPOSIT      +amount
WITHDRAWAL   -amount
BUY          -(qty * price + fee)
SELL         +(qty * price - fee)
DIVIDEND     +amount
FEE          -amount
INTEREST     +amount
```

Real external performance flow:

```text
DEPOSIT      +amount
WITHDRAWAL   -amount
all others    0
```

```text
marketValue = securitiesValue + cashBalance
netInvested = cumulative DEPOSIT - WITHDRAWAL
totalPnl    = marketValue - netInvested   # complete valuation
```

V022 bridge cash rows around legacy BUY/SELL are compatibility facts. Do not delete them as synthetic noise.

## 5. Real USD performance

Canonical endpoint remains:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

It uses the backend `PerformanceEngine`, real USD cash-inclusive valuations, and DEPOSIT/WITHDRAWAL external flows.

External-flow model:

```text
CASH_LEDGER_DEPOSIT_WITHDRAWAL
```

Only complete FRESH current valuation may extend regular-close performance with a live point.

## 6. China fund projection

V023 introduced CNY mutual-fund profiles and `auto_dca_rule`.

V024 added:

- EastMoney NAV sync;
- persisted confirmed China open dates;
- open-day NAV gap audit;
- China Funds Web workspace;
- T+n confirmation based on persisted open days;
- recent scheduled refresh.

Derived purchase rule:

```text
execution date requires exact observed NAV
open day without NAV -> gap, no execution
```

Gross CNY DCA amount:

```text
net subscription = gross / (1 + purchaseFeeRate)
purchase fee     = gross - net subscription
shares           = net subscription / NAV
```

Management fee remains metadata-only because published NAV already reflects fund-level expenses.

Rule edits currently have `REWRITE_HISTORY` semantics. Derived daily purchases are not `investment_transaction` rows.

## 7. V025 FX and reporting

V025 adds `fx_rate_daily`.

Current pair/convention:

```text
USD/CNY
1 USD = rate CNY
source = YAHOO:CNY=X
```

FX endpoints:

```text
GET  /api/v1/fx/usd-cny
POST /api/v1/fx/usd-cny/sync
```

Reporting endpoint:

```text
GET /api/v1/reporting/multicurrency?range=1M|3M|1Y|YTD|ALL
```

Core rule:

```text
historical CNY external flow -> translate at its flow-date FX
CNY market value             -> translate at valuation-date FX
```

Therefore later FX movement affects investment performance instead of retroactively rewriting historical contributions.

```text
combinedValueUsd
  = realUsdAccountValue
  + derivedCnyFundValueUsd

combinedExternalFlowUsd
  = realUsdDepositsMinusWithdrawals
  + sum(CNY DCA gross / historical flow-date FX)

combinedPnlUsd
  = combinedValueUsd - combinedExternalFlowUsd
```

Combined performance reuses the same `PerformanceEngine` through a different `PortfolioPerformanceSource`.

Reporting external-flow model when CNY activity exists:

```text
USD_CASH_LEDGER_PLUS_CNY_AUTO_DCA_AT_HISTORICAL_USDCNY
```

If there is no CNY auto-DCA history, Reporting must reduce exactly to the real USD account and require no FX.

FX carry-forward for reporting is bounded to seven calendar days. Missing required FX or an open-day NAV gap makes combined reporting `PARTIAL`; do not guess a converted value or fabricate a live endpoint.

## 8. Market-data truth

Keep separate:

```text
latest quote
raw daily close
adjusted close
fund NAV
China open day
USD/CNY FX
split event
```

- US current account valuation can use extended/overnight quote candidates;
- real USD historical performance remains regular-close;
- fund NAV and China calendar are separate persisted facts;
- FX is separate from prices/NAV;
- Reporting page reads persisted local facts and does not call Yahoo/EastMoney implicitly.

Provider failures must preserve existing local facts.

## 9. Plan / contribution boundaries

DEPOSIT funds the real USD account. BUY executes a real security purchase. A USD DCA cycle is completed by linked BUY execution, not by funding.

Real contribution analysis remains BUY-lot/FIFO based. The derived CNY auto-DCA subportfolio is not silently inserted into that real contribution analysis.

## 10. Current schema

Recent Flyway sequence:

| Migration | Meaning |
| --- | --- |
| V017 | contribution constraints/backfill/audit |
| V018 | quote session |
| V019 | invalidate untrusted snapshots |
| V020 | experimental midnight settlement |
| V021 | remove midnight settlement |
| V022 | explicit real USD cash ledger + bridge rows |
| V023 | CNY mutual-fund + auto-DCA foundation |
| V024 | China open-day calendar/gap audit |
| V025 | daily FX fact table for reporting conversion |

`portfolio_snapshot_daily` remains the real USD-account cache. There is no persisted combined-account snapshot table in V025.

## 11. Decimal/time rules

- financial values: `BigDecimal` / `NUMERIC`;
- financial JSON: decimal strings;
- timestamps: UTC;
- US business/market decisions: `America/New_York`;
- China fund calendar/provider semantics: `Asia/Shanghai`.

Do not introduce binary floating-point for stored financial values.

## 12. Current known gaps

1. Real transaction/cash ledger is still USD-only; no manual real CNY cash/fund transactions yet.
2. Reporting currency is currently USD and the FX pair is USD/CNY only.
3. Yahoo `CNY=X` and EastMoney need first-class provider-health/gap/fallback operations.
4. CNY AUTO vs future MANUAL fund activity is not unified.
5. Auto-DCA edits rewrite derived history instead of using effective-dated versions.
6. China persisted open-day history is not a future official holiday calendar.
7. Transaction/Plan/CSV live forms still contain submit-able fixed sample/default facts.
8. No concentrated DCA action queue.
9. Funding vs BUY-lot contribution analytics still need stronger UI reconciliation.
10. Full export/recovery package is incomplete.
11. Transaction/history paths still need capacity/pagination work.
12. Exact current CI evidence must be checked before release; absence of status is not a pass.

## 13. Recommended next sequence

After V025 is released:

1. **Real CNY ledger design**: define currency-aware cash accounts and manual China-fund transactions without breaking USD FIFO/cash semantics.
2. Keep reporting projection provenance explicit while real CNY facts gradually replace synthetic assumptions.
3. Remove submit-able sample facts and build the DCA action queue.
4. Add provider-health / NAV / FX gap operator views.
5. Add funding/contribution/account reconciliation and export/recovery evidence.
6. Do measured pagination/capacity cleanup.

Do not open arbitrary currencies or real CNY writes merely because `investment_transaction.currency` exists.

## 14. Validation expectations

Every functional change should pass:

- Web lint/typecheck/unit tests/build;
- API tests/build;
- PostgreSQL 18.6 `postgresTest` for schema/JPA-sensitive changes;
- relevant isolated E2E;
- repository whitespace hygiene;
- backup/restore and deployment smoke for release candidates.

For V025 accounting/reporting work specifically, keep regressions for:

```text
no CNY activity -> exact USD compatibility
CNY contribution -> historical flow-date FX
later FX move -> performance, not rewritten contribution
missing FX -> PARTIAL
open China day without NAV -> PARTIAL/no fabricated execution
```

CI tests must not depend on live provider availability.

## 15. Operations boundary

Before deploying V025:

- take/verify PostgreSQL backup;
- confirm Flyway through V025;
- verify real USD cash/securities/total controls;
- verify real USD `CASH_LEDGER_DEPOSIT_WITHDRAWAL` flow model;
- verify Reporting external-flow model when CNY activity exists;
- verify stored USD/CNY date/rate provenance;
- verify fund NAV/open-day gaps are not hidden;
- run deployment smoke and exact-head CI.

Never use `docker compose down -v` for routine deployment.

## 16. Documentation map

- `README.md` — current product/runtime entry point.
- `architecture.md` — facts/projections/modules/schema.
- `api.md` — HTTP contracts.
- `calculations.md` — USD + CNY + V025 reporting formulas.
- `market-data.md` — provider/fact/freshness/sync rules.
- `cn-fund-auto-dca-phase1.md` — V023 contract.
- `cn-fund-phase2.md` — V024 provider/calendar/UI contract.
- `multicurrency-phase3.md` — V025 FX/reporting contract.
- `operations-runbook.md` — deploy/backup/restore.
- `next-development-plan.md` — current roadmap.
- `sa-*.md` — dated historical evidence only.