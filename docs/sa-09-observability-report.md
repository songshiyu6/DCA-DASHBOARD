# SA-09 工作包报告（6.3）

> 本文是 SA-09 特定提交的历史证据，不代表当前代码全量状态。当前结论与后续工作见
> [`agent-handoff.md`](./agent-handoff.md) 和
> [`next-development-plan.md`](./next-development-plan.md)。

工作包：SA-09
分支：agent/09-observability-performance
提交：576d7a61b1637f427ce97f3437c8c5e7c1136395

## 已验证的旧行为

- `DashboardController.dashboard()` 分别调用 `summary()`、`history("1Y")`、`holdings()`、`allocation()`。
- `summary()`、`holdings()`、`allocation()` 各自调用 `ledger(today)`，重复加载当前交易、拆股、日线和最新报价并重建 FIFO。
- `history()` 使用独立的 snapshot 覆盖检查 + 按日 incremental replay，不能吃当前 ledger 投影。
- Actuator 仅暴露 `health,info`。应用代码没有 `MeterRegistry` 埋点。`spring-boot-starter-actuator` 已存在；未新增 Prometheus scrape 端点，未放宽 actuator exposure。

## 变更

### 9A Dashboard projection context

- 新增方法级 `PortfolioService.currentViews()`：一次构建 immutable current ledger，再生成 summary / holdings / allocation。
- `dashboard()` 使用 `currentViews()` + 独立 `history("1Y")`。
- 单独 REST 端点仍走各自计算。没有跨请求长期 projection cache。

### 9B Micrometer

低基数指标（tag 白名单：`provider`、`operation`、`outcome`、`status`、`mode`）：

| Metric | Type | Tags |
| --- | --- | --- |
| `dca.provider.request` | Timer | `provider`, `operation`, `outcome` |
| `dca.market.sync.rows` | Counter | `status` |
| `dca.market.sync.splits` | Counter | `status` |
| `dca.snapshot.invalidate` | Timer | `mode=from\|all` |
| `dca.snapshot.rebuild` | Timer | (none) |
| `dca.portfolio.replay` | Timer | `mode=current\|history` |
| `dca.portfolio.replay.transactions` | DistributionSummary | `mode=current\|history` |
| `dca.csv.rows` | Counter | (none) |
| `dca.csv.invalid` | Counter | (none) |
| `dca.csv.duplicate` | Counter | (none) |

`operation` ∈ `search|quote|history|intraday|profile|splits`；`outcome` ∈ `success|empty|error`。symbol / notes / credentials / SQL / provider key 不得作为 tag。未暴露 Prometheus endpoint。

### 9C Capacity baseline

生成数据：20 instruments、10,000 transactions、5 年日线 36,540 行。详见 `docs/sa-09-capacity-baseline.md`。未新增 index 或 in-memory portfolio cache。

## 新增测试

- `PortfolioDashboardProjectionEquivalenceTest`：独立方法 vs shared projection 逐字段 BigDecimal 等价；currentViews 只加载一次 current ledger；history 仍走独立 query。
- `ObservabilityMetricsTest`：`symbol` / notes / password / sql / api_key tag 必须失败；埋点后扫描全部 meter tag。
- `CapacityBaselineTest`：生成数据并记录 Hibernate 语句/实体加载耗时。

## 执行命令

- `./gradlew test --no-daemon` PASS
- `./gradlew postgresTest --no-daemon` 未跑（无 schema/persistence migration）

## 金融等价证据

`PortfolioDashboardProjectionEquivalenceTest` 在含 BUY / 2-for-1 split / SELL / DIVIDEND / FEE、quote + daily fallback、active plan allocation 的固定 Clock（2026-08-27T12:00:00Z）场景下，对 summary / holdings / allocation 全部字段 `BigDecimal.compareTo == 0`（含 null 保持 null）。history 仍从 `findAllByOrderByTradeDateAscLedgerOrderAscIdAsc` 独立 replay。

## 未解决风险

- 当前 ledger 仍一次性加载该 instrument 的全部历史日线（容量测试中 currentViews 约 46,560 entity loads，其中约 36,540 为日线）。9A 已把该成本从三次降到一次。若要再降，需要“有 quote 时不拉全量日线”的查询形态变化，而不是 index 或跨请求 cache。交给协调者决定是否另开工作包。
- 容量数字来自 H2 `application-test`，不是 PostgreSQL 18.6。生产数量级可能不同。
- 未引入 Prometheus registry / scrape endpoint。进程内 Micrometer 可用；运维抓取需后续显式暴露且保持 authenticated 或 management-only。

## 是否改变 API / schema / config

- API envelope：否
- Schema / Flyway：否
- Actuator exposure / security：否（仍为 `health,info`）
- Web / VITE：否
