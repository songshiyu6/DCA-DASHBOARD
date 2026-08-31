# DCA Terminal 当前 Agent Handoff

> 源码基线：本文件所在提交（基于 `origin/main` 的 `f119cb3`）
>
> 核对日期：2026-08-31
>
> 下一阶段规划：[`next-development-plan.md`](./next-development-plan.md)

本文是当前代码的技术交接入口，不是最初 v1 实现过程的对话归档。带日期或 SA 编号的其他报告只证明其标注提交上的结果；发生冲突时，以当前源码、Flyway migration、测试和运行探测为准。

## 1. 当前结论

DCA Terminal 已经是一套可运行的单用户 ETF 定投终端，而不是页面原型。它具备完整的行情、交易、派生持仓、计划周期、投入分析、认证、备份恢复和隔离验收链路。

Contribution/initial-capital 的事实合同已经闭环：实际初始资金只来自 `INITIAL` BUY，`V017` 在数据库层约束来源组合，未归类 BUY 通过两阶段批量确认和持久审计处理，financial decimal 以字符串无损传输。下一阶段优先清除 live 表单中的可提交样例事实并建设执行行动队列，同时补行情 gap/health 运维能力。

## 2. Git 与工作树边界

- Canonical remote：`git@github.com:songshiyu6/DCA-DASHBOARD.git`。
- 本轮实现已 rebase 到当时最新的 `origin/main` `f119cb3`；该上游提交增加扩展时段实时估值。
- 用户原有未跟踪文件包括 `AGENTS.md`、`deploy/local.override.yml` 和 `docs/orchestrator-handoff-2026-08-28.md`；不得顺手删除、覆盖或提交。
- 用户已授权提交本轮代码和文档；仍未授权 push、tag 或 release。
- `AGENTS.md` 要求所有 subagent 使用 `gpt-5.6-luna max`，需要代理的请求走 `localhost:7890`。

## 3. 当前产品功能

| 工作区 | 当前能力 | 主要事实来源 |
| --- | --- | --- |
| Dashboard | 组合总览、扩展时段当前估值、Today/YTD/TWR、XIRR、历史曲线、年度 DCA 进度和下一次建议 | transaction + split + live quote/current projection + regular-close history |
| Plan | 单 active MONTHLY plan、预算、执行窗口、ETF 权重、冻结 cycle、progress、contribution-first recommendation | plan + plan assets + linked BUY |
| Contributions | initial/DCA 分桶、批次本金/价值/P&L/ROI/持有天数、账户级未归类 BUY 批量预览/确认及审计 | classified BUY lots + SELL FIFO + current price |
| ETFs | 搜索/跟踪、quote、NAV、history、metrics、sync/full resync、provider freshness | provider-confirmed identity and market data |
| Transactions | BUY/SELL/DIVIDEND/FEE CRUD、贡献来源、cycle link、CSV preview/commit | transaction ledger |
| Settings | 主题、primary/fallback provider、key configured status | app_setting + environment capability |
| Auth | 单用户 session、CSRF、登录节流、logout | environment user + PostgreSQL Spring Session |

前端路由为 `/`、`/plan`、`/contributions`、`/etfs`、`/etfs/:symbol`、`/transactions`、`/settings` 和 `/login`。Live 模式 API 失败时显示错误，不回退到 fixture；demo 必须显式构建并持续显示标记。

## 4. 不可破坏的领域规则

### 4.1 交易是事实源

不存在可编辑 holdings API。Holding、FIFO lot、market value、P/L、allocation、XIRR、history、cycle execution 和 contribution batch 都从 ledger 重建。

任何未来改动都不得：

- 新增 `POST /portfolio/update-holdings` 一类第二事实源；
- 直接修改 snapshot 代替修复交易或行情；
- 把计划值当作实际现金；
- 把 fixture 数据混入 live 账户。

### 4.2 Decimal 与时间

- 后端金额、价格、份额、权重、收益率使用 `BigDecimal`，数据库使用 `NUMERIC`。
- 全局 Jackson serializer 把 financial `BigDecimal` 输出为普通十进制 JSON 字符串；前端 boundary normalizer 保持字符串并交给 `decimal.js-light`。数量计数和日历天数仍是 JSON number。
- Timestamp 存 UTC；business date、市场日历、交易未来日期校验和计划窗口固定使用 `America/New_York`。
- `V015` 已删除可配置 timezone setting；Settings API 不再接收或返回 timezone。

### 4.3 行情真实性

- Market price、adjusted close、NAV 分开保存和展示。
- 长期收益和 drawdown 需要 adjusted close；缺失时返回 null/降级状态，不能用 raw close 冒充。
- Portfolio historical value 使用当日 raw market close 和当日可见 ledger，不能用当前持仓乘历史价格。
- 当前 summary/holding/贡献估值优先使用最新带时间戳的 regular/pre/extended/post/overnight quote；历史图表、快照和 TWR 仍只使用常规市场日线收盘价。
- Canonical ETF catalog 只兜底身份，绝不写价格、history、NAV 或 portfolio value。
- Provider failure 与 valid empty search 必须区分。

## 5. Contribution 与初始资金语义

这是当前最容易被误读的部分。

### 5.1 Transaction fields

`V016` 给 BUY 增加来源字段，`V017` 回填可确定的 cycle-linked legacy BUY，并用 CHECK 固化组合：

- `contribution_type`: `INITIAL`, `DCA`, `UNPLANNED`, or null;
- `contribution_plan_id`: initial capital 所属 plan；
- `plan_cycle_id`: DCA 所属 cycle，已有字段。

规则：

- Cycle-linked BUY 自动是 `DCA`，不能同时提交其他 contribution type 或 plan ID。
- `INITIAL` 必须关联 plan，交易日必须等于 plan start date，且不能已关联 cycle。
- `UNPLANNED` 不关联 plan。
- SELL/DIVIDEND/FEE 不允许 contribution source。
- Legacy 或 unlinked CSV BUY 可以保持 null，由用户明确分类；系统不猜。Web 将其明确标为账户级队列，支持批量选择 `INITIAL`/`UNPLANNED`、preview、确认提交；commit 会重新加锁校验 preview hash，并在同一事务写入分类与 audit row。

### 5.2 Contribution projection

`ContributionAnalysisService` 按整个 ledger 的稳定 FIFO 顺序回放。Initial lot 与 DCA lot 只是来源标签，SELL 仍优先消耗最老 lot；split 调整份额而不改 lot 总成本。

每批结果：

```text
principal = BUY quantity * unit price + buy fee
P/L       = attributed realized P/L + open value - open cost
value     = principal + P/L
ROI       = P/L / principal
```

`averageMarketDays` 实际是按 lot cost 加权的 calendar days，不是交易日数量。当前不把 DIVIDEND 和 standalone FEE 分摊到 batch。缺少可用现价时，value/P&L/ROI 为空并降级为 PARTIAL。

Initial/DCA bucket 与 batch 按请求 plan 过滤；`unclassifiedBuys` 因尚无 plan attribution，当前是账户级队列，在分析任一 plan 时都会返回。现有 Web 只打开 active plan，因此多计划作用域问题尚未暴露在主要流程中。

### 5.3 Initial month and single-source semantics

存在实际 `INITIAL` BUY 的月份，在没有 DCA execution 时显示为零预算 `SKIPPED` DCA cycle；该月新的 cycle-linked DCA BUY 会被拒绝。没有 actual initial BUY 时，start month 正常执行 DCA。

旧数据库列 `investment_plan.initial_capital` 仅为前进兼容而保留并标记废弃；当前 JPA entity、API DTO/controller 和 Web 均不映射它。不存在 planned initial capital 写接口或 `plannedPrincipal` 响应字段，actual principal 只有 `INITIAL` BUY 一个事实源。

## 6. 组合与计划计算

- FIFO 同日顺序由 PostgreSQL `transaction_ledger_order_seq` 原子分配。
- BUY cost 包含 fee；SELL proceeds 扣 fee；standalone FEE 单独扣除；DIVIDEND 是收入。
- Snapshot 是可失效缓存。Backdated transaction 或影响历史的 split/price 变化会使相关日期之后的 snapshot 失效。
- Dashboard summary/holdings/allocation 在同一请求共享 current ledger projection；history 走独立 snapshot coverage + replay。
- XIRR 是 money-weighted return；Dashboard TWR 把 `netInvested` 变化视为 external flow，两者不能混称。
- Recommendation 只在 plan assets 范围内按正 value gap 分配新贡献，不卖出 overweight asset，金额用 largest remainder 对齐到分。
- Cycle intent 在创建/进入不可修改时间边界后冻结；未来 plan edit 不能改写历史权重和预算。

详细公式见 [`calculations.md`](./calculations.md)。

## 7. API 与 schema

当前 API 分组：

- `/api/v1/auth`
- `/api/v1/instruments`
- `/api/v1/transactions`
- `/api/v1/portfolio`
- `/api/v1/dashboard`
- `/api/v1/plans`
- `/api/v1/settings`

当前 Flyway chain：

| Migration | 关键作用 |
| --- | --- |
| `V001`-`V012` | instrument、market data、transaction、plan、snapshot 与早期一致性修复 |
| `V013` | atomic ledger-order sequence |
| `V014` | PostgreSQL-backed Spring Session |
| `V015` | 删除 obsolete timezone setting |
| `V016` | contribution source、plan attribution、optional initial target metadata |
| `V017` | legacy DCA backfill、cross-field CHECK、未分类 partial index、classification audit |

生产必须保持 `spring.jpa.hibernate.ddl-auto=validate`，不得改成 update。已发布 migration 只前进，不编辑历史文件。

完整 HTTP contract 见 [`api.md`](./api.md)。

## 8. 部署与数据安全

- Runtime：React/Vite/Nginx Web、Spring Boot 3.5/Java 21 API、PostgreSQL 18.6、Caddy。
- 只有 Caddy 暴露 host port；PostgreSQL 在 internal network。
- 正式凭据在 Git ignored、权限受限的 env 文件或 secret manager 中；不要读取、打印或提交。
- PostgreSQL 18+ volume mount 是 `/var/lib/postgresql`；major upgrade 必须 logical dump/restore。
- 永远不要运行 `docker compose down -v` 处理普通升级。
- Backup/restore 包含 transaction、plan、market data、snapshot 和 Spring Session。Restore 后需要明确是否清除旧 session。
- Caddy CSP 当前是 report-only；未做浏览器审计前不要直接切 enforcement。

当前主机的持久本地部署额外使用未跟踪的 `deploy/local.override.yml` 和 `deploy/.env`。常规升级必须保留 external PostgreSQL volume，并先备份、`gzip -t`、再构建。

操作步骤见 [`operations-runbook.md`](./operations-runbook.md)。

## 9. 2026-08-31 验证结果

### Git 与 runtime

- 本轮改造基于 `origin/main` `f119cb3`，提交前已确认上游扩展时段估值变更不与 contribution 文件冲突。
- 正式本地栈：PostgreSQL、API、Web healthy，Caddy running。
- `GET /`：HTTP 200，非空 body。
- `GET /api/health`：HTTP 200，`status=UP`。
- 持久运行库仍是本轮改造前部署状态；本轮没有擅自发布或迁移正式数据。
- 只读 contribution 组合审计：已查询的 legacy-null 与所有 source/type/cycle/plan 冲突组合当前均为 0。

### Web

- ESLint：PASS。
- TypeScript typecheck：PASS。
- Vitest：17 files、77 tests PASS。
- Production build：PASS。
- Production dependency audit：0 vulnerabilities。
- 已知 warning：main 与 charts chunk 大于 500 kB。
- Spring raw JSON 与 Web normalizer 都有 `NUMERIC(20,6/8)` 最大边界回归，证明字符串 wire contract 不经二进制浮点解析。

### API

- Java 21 下 `test build --no-build-cache --rerun-tasks`：PASS。
- `postgresTest --no-build-cache --rerun-tasks`：PASS，真实 PostgreSQL 18.6 + Flyway + Hibernate validate。
- 7 个 Spring test mock annotation deprecation warnings，当前不阻塞但应在升级前迁移。

### E2E

- `DCA_E2E_SUITE=full bash e2e/run.sh`：9/9 PASS。新增 Contributions 真实浏览器旅程创建 actual initial/legacy BUY、核对 raw decimal 字符串、批量 preview/commit，并读取 audit endpoint。
- E2E dev dependencies audit 有 2 high；不进入 production images，仍需升级 Playwright 后复测。

### 未验证

- 本轮没有得到最新 GitHub Actions job 级状态，因此不能宣称远端 CI 全绿。
- 没有对用户正式数据做破坏性 backfill、full resync 或 restore。
- 没有 push、tag、release，也没有迁移持久运行库。

## 10. 当前已知问题

1. Batch P/L 与 portfolio total 之间的 dividend/standalone-fee 差额尚未在 UI 解释。
2. Provider health 没有历史视图和 gap audit。
3. Current valuation 仍可能加载五年日线；transaction UI 仍取全量列表。
4. Contributions 页面和 Transactions 的资金来源控件有未进入 i18n catalog 的硬编码双语/英文文案。
5. Actuator metrics 未受保护地对运维系统开放，CSP 仍 report-only。
6. Live 新建交易/计划表单含固定样例默认值：日期 `2026-08-27`/`2026-01-01`，计划还预填 `Core ETF Plan`、`1500`、`VOO 100%`；CSV 弹窗把两条 `2026-09-01` 样例作为可提交初始值，而非单纯 placeholder。
7. 宿主工具入口不一致：非交互 `npm` 可能落到损坏的 `/usr/bin/npm`，`JAVA_HOME` 可能不指 Java 21。验证时显式使用 NVM npm 与 Java 21；不要把宿主问题误判为项目依赖失败。

## 11. 接任顺序

1. 阅读本文件、[`next-development-plan.md`](./next-development-plan.md)、[`calculations.md`](./calculations.md) 和当前 `AGENTS.md`。
2. 先确认 worktree、branch、origin 和用户未跟踪文件，不清理。
3. 从 roadmap `R2` 清除 live 表单可提交样例并建设执行行动队列；`R4` 可并行补行情 gap/health。
4. 扩展 contribution 分析时先解释 dividend/standalone-fee 差额，不把它们按比例猜归到批次。
5. 每个变更独立复核 diff，并重跑 Web、API、postgresTest 与相关 E2E。
6. 未获授权不 push/release；需要联网且任务要求代理时使用 `localhost:7890`。

## 12. 文档地图

- [`../README.md`](../README.md)：入口、开发、部署和设计规则。
- [`architecture.md`](./architecture.md)：模块、事实源、schema 和运行拓扑。
- [`api.md`](./api.md)：当前 HTTP contract。
- [`calculations.md`](./calculations.md)：金融、计划和 contribution 公式。
- [`market-data.md`](./market-data.md)：provider、同步、fallback 和 freshness。
- [`operations-runbook.md`](./operations-runbook.md)：部署、升级、备份、恢复和回滚。
- [`next-development-plan.md`](./next-development-plan.md)：当前优先级、路线图和 release gate。
- `sa-*.md`：历史提交证据，不是当前状态来源。
