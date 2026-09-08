# DCA Terminal Current State and Next Development Plan

> Current code baseline: `main@b6c578ee129866389efde907c10a99400da5cd4e`
>
> Current merge: PR #42, 2026-09-04
>
> Current Flyway chain: `V001`–`V022`
>
> Fact priority: current source/migrations > current tests > current runtime/CI evidence > living docs > dated SA reports

This document replaces the 2026-08-31 roadmap state. Since then, benchmark comparison, previous-regular-close Today semantics, dashboard return cleanup, cash UX, explicit cash ledger, and backend realtime performance have landed.

## 1. Product direction

DCA Terminal remains a single-user ETF DCA execution terminal. The core product loop is now:

```text
real transaction/cash ledger
      +
trusted market data
      +
frozen DCA intent
      +
cash-flow-neutral performance
      =
auditable long-term investing workspace
```

The product still does not place orders or connect to a broker. The priority is correctness, execution discipline, and explainability before adding more investment features.

## 2. Current baseline

### Dashboard

- cash-inclusive total account value;
- separate securities value and cash balance;
- Today based on previous completed regular close;
- current holdings and allocation;
- active-plan progress/next DCA;
- backend-driven `1M/3M/1Y/YTD/ALL` portfolio performance;
- optional ETF/index/equity benchmarks with market-aware freshness.

### Transactions / cash

- `DEPOSIT`, `WITHDRAWAL`, `INTEREST`, `BUY`, `SELL`, `DIVIDEND`, `FEE`;
- cash replay from immutable ledger;
- DEPOSIT/WITHDRAWAL only are external capital flows;
- BUY/SELL move value internally between cash and securities;
- server-authoritative CSV preview/commit;
- legacy V022 bridge rows preserve pre-cash-ledger economics.

### Performance

- backend `PerformanceEngine`;
- TWR, CAGR, XIRR, maximum drawdown;
- regular-close history plus optional complete FRESH live endpoint;
- external flow model `CASH_LEDGER_DEPOSIT_WITHDRAWAL`;
- benchmark comparison remains read-only and separate from portfolio facts.

### Plan / contributions

- one active monthly USD plan in current product UX;
- frozen cycle intent;
- DCA execution based on linked BUY rows;
- INITIAL/DCA/UNPLANNED BUY attribution;
- unclassified legacy BUY preview/commit + audit;
- contribution analysis remains BUY-lot/FIFO based.

### Market data

- current latest quote can include extended/overnight sessions;
- historical account performance remains regular-close based;
- 1D intraday is on-demand and non-persistent;
- Yahoo/Twelve Data/Alpha Vantage provider boundary remains intact;
- benchmark market timing supports US/default and A-share calendar/close semantics.

## 3. Completed major phases

### R1 — contribution/source contract

Completed before the current cash-ledger work:

- transaction contribution fields and constraints;
- V017 legacy deterministic backfill;
- classification preview/commit/audit;
- decimal-string wire contract regressions.

### R1.5 — daily/benchmark performance correctness

Completed through PRs #31–#38:

- Today baseline uses previous regular close instead of midnight;
- benchmark freshness/refetch follows completed market closes;
- Yahoo daily request boundaries use exchange-local calendars;
- A-share benchmark timing is market-aware;
- dashboard stopped mixing simple ROI with TWR semantics;
- complete live valuation can participate in current performance.

### R1.6 — explicit cash and server performance

Completed in PR #42 / V022:

- explicit cash transaction types;
- cash replay and cash-inclusive account value;
- migration bridge rows for legacy BUY/SELL;
- backend performance endpoint;
- external flow = DEPOSIT/WITHDRAWAL only;
- cash-neutral TWR/XIRR semantics;
- performance invalidation after ledger changes;
- core E2E updated to fund with DEPOSIT before BUY.

## 4. Current gaps

| ID | Priority | Current fact | Impact |
| --- | --- | --- | --- |
| C-01 | P0 | Living docs were stale vs V022; this synchronization closes that documentation gap. | Future work could otherwise reintroduce pre-V022 accounting semantics. |
| C-02 | P0 | Exact current remote CI/run evidence is not guaranteed by an empty connector status response. | Release claims must use concrete workflow evidence. |
| C-03 | P1 | Transaction/Plan/CSV live forms still contain submit-able fixed examples/default facts. | Sample data can be mistaken for real account facts. |
| C-04 | P1 | No centralized action queue for open/partial/missed DCA cycles. | Execution discipline remains scattered across views. |
| C-05 | P1 | Funding attribution and BUY-lot contribution attribution now coexist but are not fully explained/reconciled in UI. | Users can confuse "money funded" with "money invested". |
| C-06 | P1 | Contribution batch P/L excludes dividend, interest, standalone fee, deposit/withdrawal. | Batch totals do not directly reconcile to full account P/L without a bridge. |
| C-07 | P1 | Provider health history and expected market-data gap audit are not first-class. | Long-running data degradation may remain unnoticed. |
| C-08 | P1 | Full user export/recovery package is incomplete. | External audit/migration/recovery remains expensive. |
| C-09 | P2 | Transactions still lack pagination; current valuation/history paths can read wider data than necessary. | Scaling cost grows with ledger/history size. |
| C-10 | P2 | Some transaction/contribution strings remain hard-coded outside i18n catalogs. | Language consistency/accessibility drift. |
| C-11 | P2 | Frontend has compatibility/fallback performance logic in addition to canonical server engine. | Future semantic drift risk if both evolve independently. |

## 5. Next roadmap

### R2 — Safe execution workspace (P1)

Goal: make the current month actionable without introducing broker/order automation.

#### R2-01 Remove submit-able sample facts

Transaction form:

- remove fixed `2026-08-27` and default `VOO`;
- derive a safe current New York business date or leave explicit blank per UX decision;
- do not pre-classify a new BUY in a way that implies user intent without confirmation.

Plan form:

- remove `Core ETF Plan`, `1500`, `2026-01-01`, `VOO 100%` as submit-able defaults;
- use empty fields or clearly non-submit-able placeholders;
- asset options must come from tracked instruments.

CSV modal:

- initial textarea must be empty;
- examples belong in help/placeholder/downloadable template, not committed form state.

Acceptance: opening and immediately submitting a new form must not create plausible sample financial facts.

#### R2-02 Action queue

Create a compact queue sourced from existing plan/cycle/market-data facts:

- execution window approaching;
- OPEN in current window;
- PARTIAL execution;
- missed/SKIPPED cycle;
- current price unavailable/partial;
- unclassified BUY requiring attention.

Every action must link back to existing plan/cycle/transaction facts. No action creates a broker order.

#### R2-03 Recommendation-to-manual-BUY handoff

Allow a recommendation to prefill a manual BUY form only after explicit user action. The user must still confirm date, price, quantity, source, and cycle before transaction creation.

### R3 — Funding / contribution / performance explainability (P1)

Goal: make the post-V022 model understandable without collapsing distinct concepts.

#### R3-01 Explain three layers

UI/documentation should clearly separate:

```text
Funding:      DEPOSIT/WITHDRAWAL
Execution:    BUY/SELL and DCA cycle links
Performance:  TWR/XIRR on total cash-inclusive account
```

A deposit is not a buy; a buy is not external performance flow.

#### R3-02 Contribution/account bridge

Show an explicit reconciliation bridge instead of forcing equality:

- attributed INITIAL/DCA BUY principal;
- batch realized P/L;
- batch open P/L;
- unclassified/unplanned BUY principal;
- dividend income excluded from batch attribution;
- interest income excluded from batch attribution;
- standalone fee drag excluded from batch attribution;
- cash not yet invested;
- withdrawals/deposits as funding, not batch return.

Acceptance: users can explain why contribution-batch value differs from total account value/P&L.

#### R3-03 Provenance

Show relevant `asOf`, freshness, price source/session, and performance external-flow model where useful.

### R4 — Market-data reliability (P1)

Build first-class operator visibility:

- tracked-instrument first/last daily date;
- expected trading-day gaps;
- adjusted-close gaps;
- last successful sync;
- provider operation/outcome/latency/rate-limit history with low cardinality;
- bounded repair/retry queue;
- protected management view instead of public Actuator metrics.

Do not log secrets, cookies, full notes, SQL, or high-cardinality symbol tags in metrics.

### R5 — Export, audit, recovery (P1)

Export a safe account package containing authoritative/rebuildable facts:

- transaction ledger including ledger order/type/cash/contribution links;
- plans and frozen cycle intent;
- contribution classification audit;
- market-data provenance needed for audit where practical;
- calculation/schema/app version manifest;
- checksums.

Do not make holdings/snapshots mandatory restore facts. They should rebuild from authoritative state.

Restore smoke should validate V022 cash controls, contribution attribution, and performance controls in addition to schema startup.

### R6 — Capacity and maintenance (P2)

Measure before optimizing.

Candidates:

- transaction API pagination + server filters;
- reduce full-ledger/full-history reads for current paths;
- pass real range into history where appropriate;
- analyze current quote path loading broad daily history;
- browser table virtualization after server pagination;
- retire duplicated frontend performance formulas once fallback requirements are explicitly decided;
- move remaining strings into i18n;
- update deprecated Spring test annotations;
- bundle/route chart analysis;
- E2E dependency maintenance.

Do not use cross-request caches to hide stale or incorrect financial projections.

## 6. Recommended order

```text
Docs synced to V022
      |
      v
R2 safe execution UX/action queue
      |                \
      |                 -> R4 market reliability
      v
R3 explainability
      |                \
      |                 -> R5 export/recovery
      v
R6 measured capacity/maintenance
```

## 7. Release gates

For every functional PR:

- add/adjust regression proving the intended behavior;
- explicitly state whether transaction/cash/performance/schema semantics change;
- Web lint/typecheck/test/build;
- API test/build;
- `postgresTest` for schema/JPA/migration-sensitive changes;
- relevant isolated E2E;
- `git diff --check`;
- no real provider dependency in deterministic CI tests.

For any cash/performance PR, regression coverage must include at least one case where:

```text
DEPOSIT occurs
BUY occurs later
```

and prove the BUY does not create external TWR/XIRR flow.

For release candidate:

- exact target commit identified;
- current GitHub Actions/workflow evidence verified;
- backup/restore smoke passes;
- deployment smoke passes;
- Flyway current version confirmed;
- cash + securities + total account controls reconcile;
- performance endpoint reports expected external-flow model;
- no illegal transaction/contribution combinations;
- docs match the shipped accounting model.

## 8. Success metrics

Product/operational metrics, not investment-return targets:

- percentage of active-cycle months explicitly completed/skipped before close;
- unclassified BUY count/amount trend;
- funding-vs-invested explanation completeness;
- tracked-history/adjusted-close completeness;
- provider failure detection/recovery time;
- backup restore control-total equality;
- dashboard/transaction/contribution p95 at target ledger sizes;
- release-gate repeatability.

## 9. Explicit non-goals for the next phase

- broker integration;
- automatic order placement;
- options/crypto;
- individual-stock research product;
- technical indicators/Level 2;
- AI stock picking or price prediction;
- tax engine;
- multi-user SaaS.

Only reconsider these after the single-user ETF DCA ledger, cash, execution discipline, explainability, and recovery story are stable.
