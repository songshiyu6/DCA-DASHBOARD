# DCA Terminal Current State and Next Development Plan

> Current Flyway chain: `V001`–`V025`.
>
> Fact priority: current source/migrations > current tests > exact runtime/CI evidence > living docs > dated SA reports.

## 1. Product direction

DCA Terminal is a single-user long-term investing/DCA workspace with a real USD account ledger plus explicitly derived reporting projections.

Current architecture:

```text
real USD transaction/cash ledger
          +
trusted US market data
          +
frozen USD DCA intent
          |
          +--> real USD Dashboard / performance

CNY auto-DCA rule + NAV + China open-day facts
          |
          +--> derived CNY fund subportfolio

real USD + derived CNY + historical USD/CNY
          |
          `--> V025 unified USD Reporting projection
```

The product still does not place broker orders. Correctness, execution discipline, provenance, and recoverability remain higher priority than feature breadth.

## 2. Current baseline

### Real USD account

- explicit DEPOSIT/WITHDRAWAL/INTEREST/BUY/SELL/DIVIDEND/FEE ledger;
- cash replay + split-aware FIFO;
- `marketValue = securitiesValue + cashBalance`;
- DEPOSIT/WITHDRAWAL only are external real-account flows;
- backend TWR/CAGR/XIRR/max drawdown;
- regular-close history + optional complete FRESH live endpoint;
- Dashboard remains the real USD-account surface.

### China funds

- CNY mutual-fund metadata and observed NAV;
- persisted confirmed China open dates;
- EastMoney NAV/open-day sync with failure-preserves-local-facts behavior;
- automatic-DCA rules with purchase fees and T+n confirmation;
- open day without NAV is a visible gap and never creates a derived purchase;
- monthly/yearly aggregation plus daily drilldown;
- rule edits currently rewrite derived history.

### V025 FX / Reporting

- persisted `fx_rate_daily`;
- USD/CNY convention `1 USD = rate CNY`;
- Yahoo `CNY=X` sync + recent scheduler;
- CNY DCA flows translated at historical flow-date FX;
- CNY market values translated at valuation-date FX;
- seven-day FX carry bound;
- missing required FX/NAV -> PARTIAL, no fabricated live endpoint;
- combined USD value/P&L/TWR/CAGR/XIRR/drawdown reuse the existing `PerformanceEngine`;
- no-CNY activity exactly reduces to the existing USD account;
- Reporting is separate from Dashboard and is not a real multi-currency cash ledger.

## 3. Completed major phases

### R1 — contribution/source contract

- contribution fields/constraints;
- deterministic legacy backfill;
- classification preview/commit/audit;
- decimal-string wire regressions.

### R1.5 — daily/benchmark correctness

- previous-regular-close Today baseline;
- market-aware benchmark freshness;
- exchange-local Yahoo history boundaries;
- dashboard return semantics aligned with TWR;
- complete live valuation support.

### R1.6 — explicit real USD cash + server performance

Completed through V022:

- explicit cash events;
- cash-inclusive real account;
- legacy bridge rows;
- canonical backend performance;
- real external flow = DEPOSIT/WITHDRAWAL only.

### R1.7 — China-fund automatic-DCA projection

Completed through V023–V024:

- CNY mutual-fund profile/rules;
- observed NAV projection;
- confirmed China open-day facts;
- NAV gap audit;
- EastMoney sync/scheduler;
- China Funds workspace and summaries.

### R1.8 — FX-backed unified reporting

Completed in V025 / Phase 3:

- persisted daily FX facts;
- historical-flow vs valuation FX separation;
- combined USD reporting source;
- combined performance via existing `PerformanceEngine`;
- Reporting workspace + manual FX refresh;
- missing-data downgrade and no-CNY compatibility regressions.

## 4. Current gaps

| ID | Priority | Current fact | Impact |
| --- | --- | --- | --- |
| C-01 | P0 | Real transaction/cash ledger is still USD-only. | Manual real CNY fund/cash activity cannot replace synthetic reporting assumptions yet. |
| C-02 | P0 | Exact remote CI evidence must be verified for each release head. | A stale/empty status response is never a pass. |
| C-03 | P1 | USD/CNY is the only reporting FX pair and USD the only reporting currency. | No arbitrary currency matrix/reporting selection. |
| C-04 | P1 | Yahoo `CNY=X` and EastMoney lack first-class health/fallback/gap operator views. | Long-running external data degradation may go unnoticed. |
| C-05 | P1 | Auto-DCA rule edit still has `REWRITE_HISTORY` semantics. | Historical derived reporting can change after a rule edit. |
| C-06 | P1 | China open dates are persisted confirmed history, not a future official holiday calendar. | Future-date scheduling must not assume complete exchange calendar coverage. |
| C-07 | P1 | Transaction/Plan/CSV forms still contain submit-able fixed sample/default facts. | Sample values can be mistaken for real account activity. |
| C-08 | P1 | No centralized action queue for open/partial/missed USD DCA cycles. | Execution discipline remains scattered across views. |
| C-09 | P1 | Funding attribution vs BUY-lot contribution attribution is not fully reconciled in UI. | Users can confuse money funded with money invested. |
| C-10 | P1 | Contribution batch P/L excludes dividend/interest/standalone fee/funding flows. | Batch totals require an explicit bridge to full account P/L. |
| C-11 | P1 | Full user export/recovery package is incomplete. | External audit/migration/recovery remains expensive. |
| C-12 | P2 | Transactions lack pagination; some current/history paths read wider data than necessary. | Cost grows with ledger/history size. |
| C-13 | P2 | Some copy remains outside i18n catalogs. | Language/accessibility drift. |
| C-14 | P2 | Frontend compatibility performance logic remains alongside canonical server logic. | Future semantic drift risk. |

## 5. Next roadmap

### R2 — Real CNY ledger foundation (P0/P1)

Goal: allow real CNY cash/fund activity without weakening the proven USD ledger.

Do **not** simply remove the current `currency=USD` guard.

Design first:

- explicit currency-aware cash balances;
- legal transaction currency/instrument combinations;
- CNY DEPOSIT/WITHDRAWAL/INTEREST/FEE semantics;
- manual real mutual-fund BUY/SELL or subscription/redemption representation;
- currency-aware FIFO/cost/proceeds;
- external-flow semantics in reporting currency;
- migration compatibility for existing USD rows;
- relationship between real CNY transactions and existing auto-DCA synthetic rules.

Acceptance:

- existing USD-only accounts remain numerically identical;
- CNY cannot make USD cash negative/positive by accidental cross-currency arithmetic;
- every real transaction has an unambiguous cash-account currency;
- combined Reporting can prefer real CNY facts without double-counting synthetic derived history.

### R2.1 — Effective-dated auto-DCA rules

Replace unconditional rewrite-history edits with an explicit model such as:

```text
rule version A effective [start, changeDate)
rule version B effective [changeDate, ...)
```

Keep an explicit administrative rewrite option only when the user intentionally wants historical reconstruction changed.

### R2.2 — FX/provider reliability

Build operator visibility for:

- first/last FX date;
- expected recent FX gaps;
- last successful FX sync;
- Yahoo `CNY=X` provider health/history;
- EastMoney NAV/open-day provider health;
- bounded repair/retry queue;
- alternate FX/fund source strategy before claiming automatic fallback.

Never fabricate fallback from an unrelated rate/source.

### R3 — Safe execution workspace (P1)

#### Remove submit-able sample facts

Transaction form:

- remove fixed dates/symbols;
- use safe current date or blank state;
- do not imply contribution intent before confirmation.

Plan form:

- remove fixed plan name/budget/start date/VOO target;
- use empty fields/placeholders;
- source assets from real tracked instruments.

CSV modal:

- initial textarea empty;
- examples belong in help/template, not committed form state.

#### Action queue

Centralize:

- upcoming/open/partial/skipped cycles;
- price/NAV/FX data gaps requiring attention;
- unclassified real BUYs;
- failed fund/FX sync needing operator action.

No action creates a broker order.

### R4 — Funding / contribution / reporting explainability (P1)

Show four layers explicitly:

```text
Real funding:       DEPOSIT/WITHDRAWAL
Real execution:     BUY/SELL + plan cycle links
Derived CNY plan:   rule + NAV/open-day reconstruction
Reporting:          USD conversion + TWR/XIRR
```

Add reconciliation for:

- attributed INITIAL/DCA real BUY principal;
- unplanned/unclassified real BUYs;
- real cash not invested;
- dividend/interest/standalone fees;
- funding flows;
- derived CNY capital and its FX effect.

### R5 — Export, audit, recovery (P1)

Export authoritative/rebuildable facts:

- real transaction ledger + order/currency/attribution;
- plans/frozen cycles;
- contribution audit;
- China fund profiles/rules/NAV/open days;
- FX facts and source provenance;
- calculation/schema/app manifest;
- checksums.

Holdings/snapshots/reporting output should rebuild rather than become required restore facts.

### R6 — Capacity and maintenance (P2)

Measure before optimizing:

- transaction pagination/server filters;
- narrower current/history reads;
- real server-side range forwarding;
- quote-path broad-history analysis;
- table virtualization after pagination;
- frontend performance-fallback retirement decision;
- i18n cleanup;
- deprecated Spring test annotation cleanup;
- bundle/E2E dependency maintenance.

## 6. Recommended order

```text
V025 reporting
    |
    +--> R2 real CNY ledger design/implementation
    |       |
    |       +--> R2.1 effective-dated rules
    |
    +--> R2.2 FX/fund provider reliability
    |
    +--> R3 safe execution UX
            |
            v
         R4 explainability
            |
            +--> R5 export/recovery
            |
            `--> R6 measured capacity
```

## 7. Release gates

For every functional PR:

- add/adjust deterministic regressions;
- state whether real ledger, projection, performance, schema, or provider semantics change;
- Web lint/typecheck/test/build;
- API test/build;
- PostgreSQL 18.6 Flyway/Hibernate validation for schema-sensitive changes;
- relevant isolated E2E;
- repository whitespace hygiene;
- no live provider dependency in deterministic CI tests.

For reporting/FX changes, regressions should cover as relevant:

```text
no CNY -> exact USD compatibility
historical CNY contribution -> flow-date FX
later FX movement -> performance, not rewritten flow
missing/stale FX -> PARTIAL
open China day without NAV -> PARTIAL/no invented purchase
```

For release candidate:

- exact target head identified;
- exact GitHub Actions run verified successful;
- PostgreSQL backup/restore smoke successful;
- deployment smoke successful;
- Flyway current version confirmed;
- real USD controls reconcile;
- real USD and Reporting external-flow models are correct;
- docs match shipped semantics.

## 8. Product/operational success metrics

Not investment-return targets:

- active-cycle completion/skip discipline;
- unclassified real BUY trend;
- NAV/open-day/FX data completeness;
- provider failure detection/recovery time;
- backup restore control equality;
- reporting reconciliation completeness;
- dashboard/reporting/transaction p95 at target history sizes;
- repeatable release gates.

## 9. Explicit non-goals

- broker integration or automatic order placement;
- options/crypto;
- stock-picking/research product;
- technical indicators/Level 2;
- AI price prediction;
- tax engine;
- multi-user SaaS.

Only revisit these after the single-user ledger, cross-currency facts, execution discipline, explainability, and recovery story are stable.