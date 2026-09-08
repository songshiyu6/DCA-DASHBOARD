# DCA Terminal

> Current Flyway chain: `V001` through `V023`.

DCA Terminal is a focused single-user investing and DCA dashboard. The established production path remains USD-first for US ETFs, while V023 adds the first backend foundation for CNY China mutual funds and rule-driven automatic-DCA projections.

The product is designed to answer four questions with auditable data:

1. What actually happened in the account?
2. What does the account own and how much cash is available now?
3. Is the DCA plan being executed as intended?
4. What investment performance remains after external funding and withdrawals are removed?

The project is not a broker, order-entry system, trading terminal, research product, or financial-advice service. It deliberately excludes broker APIs, automatic order placement, options, cryptocurrency, individual-stock research, technical indicators, price prediction, news, social features, paper trading, tax calculation, and multi-user SaaS.

## Current product

The authenticated Web application currently exposes these established workspaces:

- **Dashboard** — cash-inclusive USD account value, security holdings, Today P/L, plan progress, allocation, and investment-performance chart.
- **Plan** — one active monthly USD plan, frozen monthly cycles, execution windows, target weights, progress, and contribution-first recommendations.
- **Contributions** — plan-scoped `INITIAL`/`DCA` BUY-lot analysis, unclassified legacy BUY queue, classification preview/commit, and audit history.
- **ETFs** — tracked US ETF identity, quote, NAV when available, daily/intraday history, metrics, sync, and provider freshness.
- **Transactions** — `DEPOSIT`, `WITHDRAWAL`, `INTEREST`, `BUY`, `SELL`, `DIVIDEND`, and `FEE`, plus server-authoritative CSV preview/commit.
- **Settings** — theme and market-data provider selection/configuration status.

V023 additionally exposes backend APIs for CNY domestic mutual-fund metadata/NAV and automatic-DCA rules. The China-fund UI, FX conversion, and integration into the total portfolio are later phases; V023 deliberately does not pretend those pieces already exist.

The performance panel supports `1M`, `3M`, `1Y`, `YTD`, and `ALL`, and can overlay arbitrary Yahoo-searchable ETF, index, or equity benchmarks without adding those benchmarks to the tracked-instrument/portfolio domain.

## Source of truth

The transaction ledger is the source of truth for real account activity. There is no editable holdings state and no editable cash-balance state.

```text
transactions + splits + market data
             |
             +--> security FIFO lots / holdings
             +--> cash ledger
             |
             v
      cash-inclusive portfolio value
             |
             +--> dashboard / allocation / snapshots
             +--> TWR / CAGR / XIRR / drawdown
             `--> plan/contribution projections
```

China-fund automatic DCA introduces a separate, explicit source fact for **synthetic historical projection only**:

```text
auto_dca_rule + observed fund NAV dates
                    |
                    v
          derived daily purchases
                    |
              +-----+-----+
              |           |
           monthly      yearly
           summary      summary
```

`auto_dca_rule` does **not** create `investment_transaction` rows. Editing the rule changes its derived history on the next calculation. Daily automatic-DCA rows are rebuildable projections and are hidden by default in the API.

Daily portfolio snapshots are also rebuildable caches. They may be invalidated after backdated transaction, split, or historical-price changes and must never become a second fact source.

### Cash ledger

Cash is replayed from the ordered real transaction ledger:

| Event | Cash effect | External performance flow? |
| --- | ---: | --- |
| `DEPOSIT` | `+amount` | yes |
| `WITHDRAWAL` | `-amount` | yes |
| `BUY` | `-(quantity * price + fee)` | no |
| `SELL` | `+(quantity * price - fee)` | no |
| `DIVIDEND` | `+amount` | no |
| `FEE` | `-amount` | no |
| `INTEREST` | `+amount` | no |

Only `DEPOSIT` and `WITHDRAWAL` are external capital flows. Buying or selling a security moves value between cash and securities inside the account and therefore must not create or remove investment performance.

The current real-account summary contract remains:

```text
securitiesValue = sum(open shares * current security price)
cashBalance     = replay(all cash effects)
marketValue     = securitiesValue + cashBalance
netInvested     = cumulative DEPOSIT - WITHDRAWAL
totalPnl        = marketValue - netInvested        # when valuation is complete
```

`marketValue` is therefore total account value, despite the historical field name.

V023 does **not** yet make the real ledger multi-currency. CNY automatic-DCA projections stay outside the USD cash ledger until the later multi-currency ledger/FX phase is implemented.

### Legacy cash migration

`V022__introduce_cash_ledger.sql` preserves the economic meaning of pre-cash-ledger accounts by inserting deterministic bridge cash events around legacy BUY/SELL rows:

- a legacy BUY receives a preceding synthetic `DEPOSIT` for its cash outlay;
- a legacy SELL receives a following synthetic `WITHDRAWAL` for its net proceeds;
- unusual negative SELL proceeds are bridged defensively;
- existing snapshots are invalidated because pre-V022 history did not contain explicit cash.

These rows are migration facts used to preserve compatibility. Do not delete them just because they are system-generated.

## China mutual funds and automatic DCA

V023 adds `instrument_type=MUTUAL_FUND` and allows CNY instruments, plus:

- `fund_profile` — annual management-fee metadata, share class, fund-calendar identifier, configurable confirmation trading-day lag;
- `auto_dca_rule` — CNY amount, start/end date, `DAILY_FUND_TRADING_DAY`, purchase-fee rate, and enabled state;
- fund/NAV APIs under `/api/v1/funds`;
- automatic-DCA rule and projection APIs under `/api/v1/auto-dca`.

Historical automatic-DCA purchases are derived from **observed NAV dates**. This means a complete NAV series naturally excludes weekends and China holidays without inventing a scheduled order. It also means a missing provider NAV row cannot yet be distinguished from a genuinely closed day; a first-class China fund calendar and provider gap audit are a later phase.

The configured DCA amount is treated as total cash paid. For a purchase-fee rate `r`:

```text
net subscription = gross amount / (1 + r)
purchase fee     = gross amount - net subscription
shares           = net subscription / NAV
```

NAV date and confirmation date are separate. The fund profile controls how many subsequent observed fund trading days are used for confirmation timing. Confirmation delay does not change the purchase NAV date.

Management fee is metadata only. Published mutual-fund NAV already reflects fund-level accrued expenses, so the application must not deduct the annual management fee from NAV again.

Projection analysis defaults to monthly aggregation, supports yearly aggregation, and returns daily rows only when explicitly requested with `includeDaily=true`.

See [`docs/cn-fund-auto-dca-phase1.md`](docs/cn-fund-auto-dca-phase1.md) for the exact Phase 1 contract and boundaries.

## Performance semantics

Portfolio performance is calculated by the backend `performance` module and exposed at:

```text
GET /api/v1/performance/portfolio?range=1M|3M|1Y|YTD|ALL
```

The engine uses:

- regular-close cash-inclusive portfolio valuations for historical points;
- at most one current live endpoint;
- only a complete `FRESH` live valuation, so a missing quote cannot manufacture a loss;
- `DEPOSIT - WITHDRAWAL` as the external-flow stream;
- TWR for capital-flow-neutral performance;
- XIRR for money-weighted personal return;
- CAGR and maximum drawdown from the performance level series.

The dashboard may retain local calculation code as a compatibility fallback, but the server performance endpoint is the canonical live contract.

Today performance is anchored to the previous completed regular close, not midnight and not the newest same-day stored close. `V020` created an experimental midnight-settlement table; `V021` removed it while preserving forward-only Flyway history.

CNY mutual-fund projections are not yet included in this portfolio performance engine. That requires historical FX and a multi-currency account model and is intentionally deferred rather than mixing CNY and USD numerically.

## Contribution semantics

Existing plan execution and contribution-batch analysis remain based on actual BUY transactions:

- a cycle-linked BUY is `DCA`;
- an `INITIAL` BUY is linked to a plan and must occur on the plan start date;
- `UNPLANNED` BUYs remain outside plan contribution totals;
- legacy unclassified BUYs can be previewed and committed through the classification workflow;
- SELL uses the same global split-aware FIFO lots and can realize P/L from an attributed batch.

`DEPOSIT` is funding, not DCA execution. The backend can store `INITIAL`/`DCA`/`UNPLANNED` funding attribution on DEPOSIT rows, but the current Contributions projection remains BUY-lot based. Do not silently reinterpret a deposit as an executed purchase.

The V023 China-fund automatic-DCA projection is intentionally separate from this real BUY-lot analysis until multi-currency real transactions and the combined AUTO/MANUAL analysis are implemented.

## Market-data semantics

Market price, adjusted close, and fund NAV are different facts and remain separate.

- **Current US ETF valuation** prefers the newest valid timestamped regular/pre-market/post-market/extended/overnight quote.
- **Historical US portfolio valuation** uses regular-session raw closes and historical ledger replay.
- **ETF return metrics** use adjusted close where documented.
- **Fund NAV** is stored in `fund_nav_daily`, separately from market price.
- **1D chart** uses on-demand provider bars and is not persisted as a five-minute database.
- Provider errors, a valid pre-open empty response, a closed market, and a post-open data anomaly are distinct states.

China mutual funds created through `/api/v1/funds` are kept out of the legacy tracked-US-ETF feed in V023, so the existing ETF scheduler/provider path cannot accidentally try to synchronize them as US securities.

Benchmark history is isolated from tracked instruments and portfolio facts. ETF/index/equity benchmark selection is read-only and browser-persisted; it does not create an instrument, holding, or transaction.

## Repository layout

```text
.
├── apps/
│   ├── web/                 # React 19 + TypeScript + Vite
│   └── api/                 # Spring Boot 3.5.16 + Java 21
├── deploy/
│   ├── docker-compose.yml   # web, api, postgres, caddy
│   ├── docker-compose.e2e.yml
│   ├── Caddyfile
│   ├── .env.example
│   └── scripts/             # backup, restore, deployment smoke
├── e2e/                     # Playwright against isolated mock-provider stack
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── calculations.md
│   ├── market-data.md
│   ├── cn-fund-auto-dca-phase1.md
│   ├── operations-runbook.md
│   ├── agent-handoff.md
│   └── next-development-plan.md
└── .github/workflows/ci.yml
```

The API is a modular monolith. Current modules include `benchmark`, `fund`, `instrument`, `marketdata`, `transaction`, `portfolio`, `performance`, `plan`, `settings`, `security`, and `observability`.

## Runtime and configuration

Expected local toolchain:

- Node.js 22 for `apps/web`;
- Java 21 for `apps/api`;
- Docker Engine + Docker Compose v2 for the full stack.

Production Compose defaults to PostgreSQL `18.6-alpine`. PostgreSQL 18+ uses the parent `/var/lib/postgresql` mount with a versioned data directory beneath it. A data directory from PostgreSQL 16 or another major version must be upgraded with logical dump/restore; changing only the image tag is not an upgrade.

Create the untracked deployment environment file:

```bash
cp deploy/.env.example deploy/.env
${EDITOR:-vi} deploy/.env
```

Set real values for at least `APP_DOMAIN`, `CADDY_EMAIL`, `POSTGRES_PASSWORD`, `APP_USERNAME`, and `APP_PASSWORD_HASH`. The application currently uses Spring BCrypt password encoding; do not commit plaintext passwords, password hashes, provider keys, cookies, sessions, or database credentials.

`TWELVE_DATA_API_KEY` and `ALPHA_VANTAGE_API_KEY` are optional server-side provider credentials. `YAHOO_PROXY_URL` is also server-side. Provider credentials must never become Vite variables or browser-visible API fields.

Existing US business dates, plan windows, market-session boundaries, and portfolio day rollover use `America/New_York`. Database timestamps remain UTC. China-fund V023 history uses observed NAV dates and does not yet claim a future China holiday calendar.

## Development

Web:

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm test -- --run
npm run build
```

API:

```bash
cd apps/api
./gradlew test build --no-daemon
./gradlew postgresTest --no-daemon
```

Deployment graph:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

Isolated E2E:

```bash
DCA_E2E_SUITE=smoke bash e2e/run.sh
# or explicitly request the full suite
DCA_E2E_SUITE=full bash e2e/run.sh
```

Do not run E2E against a production volume or production `.env`.

## CI

`.github/workflows/ci.yml` currently checks:

- Web install, production dependency audit, lint, typecheck, unit tests, and build;
- API tests/build on Java 21;
- PostgreSQL 18.6 Flyway + Hibernate validation through `postgresTest`;
- Compose and shell-script validation;
- PostgreSQL backup/restore smoke;
- temporary Caddy/API deployment smoke;
- isolated Playwright E2E;
- repository whitespace hygiene.

The npm production-audit gate fails on actual vulnerability findings but treats recognized npm-registry/audit-service outages as a warning rather than misreporting them as dependency success or failure.

A missing GitHub status response is not evidence that CI passed. Release claims must reference a concrete current workflow run or equivalent verified evidence.

## Flyway state

Current published chain on this feature: `V001`–`V023`.

Key recent migrations:

| Migration | Purpose |
| --- | --- |
| `V013` | atomic transaction ledger-order sequence |
| `V014` | PostgreSQL-backed HTTP sessions |
| `V015` | remove obsolete timezone setting |
| `V016` | contribution tracking fields |
| `V017` | contribution-source constraints, legacy backfill, classification audit |
| `V018` | latest-quote session classification |
| `V019` | remove snapshots that could not be trusted under corrected replay semantics |
| `V020` | create experimental midnight settlement table |
| `V021` | remove midnight settlement; restore previous-regular-close daily semantics |
| `V022` | explicit cash ledger, new cash transaction types, cash-inclusive snapshots/performance |
| `V023` | CNY mutual-fund profile and rule-driven automatic-DCA projection foundation |

Migrations are forward-only. Never edit an already published migration to make a later application version look compatible.

## Deployment and backups

Production topology:

```text
Internet -> Caddy (80/443) -> web
                         `-> /api/* -> Spring Boot -> PostgreSQL
```

Only Caddy publishes host ports. PostgreSQL stays on the internal network.

Before an upgrade that can change schema or financial projections:

```bash
deploy/scripts/backup-postgres.sh
gzip -t deploy/backups/daily/<backup>.sql.gz
```

Then rebuild the stack without deleting volumes:

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

Never use `docker compose down -v` as a normal upgrade step.

See `docs/operations-runbook.md` for backup, restore, PostgreSQL-major-upgrade, session, and smoke procedures.

## Current known gaps

The current code is functional, but important work remains:

- CNY mutual-fund automatic DCA is backend-only in V023; UI is not implemented yet;
- live/historical FX and USD reporting conversion are not implemented yet;
- the real transaction/cash ledger is still USD-only and must not numerically mix CNY;
- automatic China-fund NAV ingestion and first-class China holiday/open-day gap audit are not implemented yet;
- AUTO vs MANUAL fund-investment analysis is not yet unified;
- rule versioning for “change from this date forward” is not yet implemented; current `PUT` explicitly rewrites derived history;
- live transaction, plan, and CSV forms still contain fixed sample/default facts that should be removed;
- the UI needs a concentrated DCA action queue for open/partial/missed cycles;
- provider health history and market-data gap audit are not yet first-class operator views;
- full account export/recovery evidence is incomplete;
- transaction list/history paths still need capacity-oriented pagination/range work;
- some contribution/transaction copy remains outside the i18n catalog.

The authoritative prioritized list remains `docs/next-development-plan.md`; the China-fund phase contract is `docs/cn-fund-auto-dca-phase1.md`.

## Documentation map

- [Architecture](docs/architecture.md) — boundaries, modules, facts, projections, schema, runtime.
- [API](docs/api.md) — established HTTP contract.
- [Calculations](docs/calculations.md) — cash, portfolio, performance, ETF, FIFO, plan, contribution formulas.
- [Market data](docs/market-data.md) — provider, quote/history/intraday/benchmark, retry, freshness.
- [China fund auto-DCA Phase 1](docs/cn-fund-auto-dca-phase1.md) — CNY fund/NAV/rule projection contract and limitations.
- [Operations runbook](docs/operations-runbook.md) — deploy, upgrade, backup, restore, smoke, rollback.
- [Agent handoff](docs/agent-handoff.md) — established implementation state and takeover rules.
- [Next development plan](docs/next-development-plan.md) — current gaps, priorities, release gates.

Files named `docs/sa-*.md` are dated historical evidence for the commits they name. They are intentionally not rewritten into current-state documents.
