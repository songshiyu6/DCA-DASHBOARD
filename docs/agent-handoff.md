# DCA Terminal Current Agent Handoff

> Current code baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`
>
> Current merge: PR #42, 2026-09-04
>
> Current Flyway chain: `V001`–`V022`
>
> Next priorities: [`next-development-plan.md`](./next-development-plan.md)

This is the current technical handoff entry point. Dated `sa-*.md` files are historical evidence only. If anything conflicts, use current source/migrations/tests/runtime evidence first.

## 1. Current conclusion

DCA Terminal is a working single-user ETF DCA/account terminal with explicit cash accounting and backend portfolio-performance calculation.

The largest semantic change since the previous 2026-08-31 handoff is V022 + PR #42:

- cash is now a first-class projection of the transaction ledger;
- total account value includes securities + cash;
- DEPOSIT/WITHDRAWAL are the only external performance cash flows;
- BUY/SELL are internal transfers between cash and securities;
- current performance is served by the backend performance engine;
- legacy accounts are migrated with deterministic bridge cash rows so their historical economic meaning is preserved.

Do not continue using the pre-V022 "BUY/SELL change in net invested" model for current performance work.

## 2. Canonical repository and source priority

Canonical repository:

```text
https://github.com/songshiyu6/DCA-DASHBOARD
```

Current source priority:

```text
current source + published migration
> current tests
> current runtime / CI evidence
> living docs
> dated sa-*.md reports
```

Do not rewrite historical SA evidence to look current.

## 3. Current product surface

| Workspace | Current capability |
| --- | --- |
| Dashboard | cash-inclusive account total, current holdings, Today, plan progress, allocation, performance chart/benchmarks |
| Plan | one active monthly USD plan, target weights, frozen cycles, execution status, contribution-first recommendation |
| Contributions | INITIAL/DCA BUY-lot attribution, unclassified BUY queue, preview/commit classification, audit |
| ETFs | tracked ETF search/profile/quote/NAV/history/metrics/sync/freshness |
| Transactions | DEPOSIT/WITHDRAWAL/INTEREST/BUY/SELL/DIVIDEND/FEE CRUD + CSV preview/commit |
| Settings | theme and provider selection/configuration status |
| Auth | single-user PostgreSQL-backed session + CSRF + throttle |

Benchmark comparison supports Yahoo-searchable ETF, INDEX, and EQUITY without adding them to tracked portfolio instruments.

## 4. Non-negotiable domain rules

### 4.1 Ledger truth

There is no editable holdings or cash balance state.

```text
transactions + splits + prices -> holdings / cash / portfolio / performance
```

Snapshots are disposable caches. Fix bad facts at the transaction/market-data layer, not by editing snapshots.

### 4.2 Current cash semantics

Cash change:

```text
DEPOSIT      +amount
WITHDRAWAL   -amount
BUY          -(qty * price + fee)
SELL         +(qty * price - fee)
DIVIDEND     +amount
FEE          -amount
INTEREST     +amount
```

External performance flow:

```text
DEPOSIT      +amount
WITHDRAWAL   -amount
all others    0
```

Current total account value:

```text
marketValue = securitiesValue + cashBalance
netInvested = cumulative DEPOSIT - WITHDRAWAL
```

For a complete valuation:

```text
totalPnl = marketValue - netInvested
```

### 4.3 V022 bridge rows

V022 inserts system-generated legacy cash rows around existing BUY/SELL records. They are required compatibility facts, not disposable noise.

Do not delete or deduplicate them without proving the full migrated economic history remains identical.

### 4.4 Performance

Canonical endpoint:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

Backend engine calculates TWR/CAGR/XIRR/max drawdown from cash-inclusive valuations and DEPOSIT/WITHDRAWAL external flow.

Only a complete FRESH live total account valuation can extend regular-close performance history.

### 4.5 Contribution vs funding

DEPOSIT funds the account. BUY executes a security purchase.

A DCA cycle is completed by linked BUY execution, not by deposit funding.

Contribution analysis remains BUY-lot based even though DEPOSIT may carry source attribution in the current schema/API.

### 4.6 Decimal/time

- financial values: BigDecimal/NUMERIC;
- API financial values: decimal JSON strings;
- Web financial math: decimal.js-light;
- timestamps: UTC;
- US business/plan date: America/New_York.

Do not introduce Java/DB binary floating-point for financial values.

### 4.7 Market-data truth

Keep latest quote, daily raw close, adjusted close, NAV, and split events separate.

Current valuation may move after-hours; historical regular-close performance does not retroactively become intraday/overnight data.

## 5. Current schema state

Current Flyway: `V001`–`V022`.

Recent sequence:

| Migration | Meaning |
| --- | --- |
| V017 | contribution constraints / audit |
| V018 | quote session |
| V019 | remove untrusted snapshots |
| V020 | experimental midnight settlement |
| V021 | remove midnight settlement |
| V022 | explicit cash ledger and cash-inclusive account model |

V020/V021 remain in history even though the final runtime semantics use previous regular close, not midnight settlement.

## 6. Current APIs added since the old handoff

Important current surfaces include:

```text
/api/v1/benchmarks/search
/api/v1/benchmarks/history
/api/v1/performance/portfolio
```

Portfolio summary/history now expose cash/securities breakdown while retaining compatibility field names.

Transactions now accept account cash events.

## 7. Current known gaps

The important open gaps are now:

1. **Live form sample facts remain unsafe UX**: transaction form still defaults to `2026-08-27`/VOO, plan form to `Core ETF Plan`/1500/`2026-01-01`/VOO 100%, CSV modal still contains submit-able 2026-09-01 example rows.
2. No concentrated action queue for open/partial/missed DCA cycles.
3. Funding attribution vs BUY-lot contribution analytics is not yet fully reconciled in user-facing explanation.
4. Contribution batch P/L still excludes dividends, interest, and standalone fees; UI needs an explicit bridge rather than hidden mismatch.
5. Provider health history and expected market-data gap audit are not first-class views.
6. Full user export/recovery package remains incomplete.
7. Current valuation/history/transaction list still have capacity work: wide history reads, no transaction pagination, some client-side range/filter behavior.
8. Some transaction/contribution labels remain outside i18n catalogs.
9. Remote CI for a given commit must be explicitly verified; absence of returned status is not a pass.

## 8. Recommended next sequence

1. Remove live submit-able sample facts from Transaction / Plan / CSV forms.
2. Build the DCA action queue on existing plan/cycle facts.
3. Add a clear cash-funding vs BUY-execution vs performance/contribution explanation bridge.
4. Build market-data gap/provider-health operator views in parallel.
5. Add export/recovery audit package.
6. Only then prioritize capacity/performance cleanup where measurements justify it.

See `next-development-plan.md` for acceptance gates.

## 9. Validation expectations

For every functional change:

- Web lint, typecheck, unit tests, production build;
- API test/build;
- `postgresTest` for JPA/Flyway/schema-sensitive changes;
- relevant isolated E2E;
- `git diff --check`;
- no provider live calls in deterministic CI tests.

For accounting/performance changes, add regression cases that explicitly separate:

```text
DEPOSIT funding
BUY execution
SELL proceeds
WITHDRAWAL external flow
DIVIDEND / INTEREST internal return
FEE internal drag
```

Do not accept a test that passes only because BUY is still being treated as external capital.

## 10. Release/operations boundary

Before production deployment of V022-era code:

- verified backup;
- Flyway V022 confirmation;
- current cash/securities/total controls;
- performance externalFlowModel check;
- transaction/contribution consistency check;
- deployment smoke;
- explicit current CI evidence.

Never use `down -v` for routine deployment.

## 11. Documentation map

- `README.md` — current product/runtime entry point.
- `architecture.md` — current facts, projections, modules, schema.
- `api.md` — current HTTP contract.
- `calculations.md` — post-V022 formulas.
- `market-data.md` — provider/quote/history/intraday/benchmark rules.
- `operations-runbook.md` — deploy/backup/restore/V022 validation.
- `next-development-plan.md` — current prioritized roadmap.
- `sa-*.md` — dated historical evidence only.
