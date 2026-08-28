# DCA Terminal 下一阶段开发计划（Subagent 执行版）

> 基线：`main@b12945ff5d5879770396a7a36b22f83396e11a08`
>
> 编制日期：2026-08-28
>
> 目标里程碑：**v1.1 — Truthful, Rebuildable, Operable**
>
> 本文优先遵循 [`agent-handoff.md`](./agent-handoff.md)，并以当前代码、测试和 GitHub Actions 运行结果进行交叉验证。本文不是重新设计产品，而是把已经完成的 v1 纵向能力收敛成一个在真实数据、故障和恢复场景下可信的个人投资终端。

---

## 1. 如何使用本文

本文面向一个主协调 agent 和多个能力较低、上下文较短的 subagent。不要把整份文档一次性交给一个 subagent，也不要让多个 subagent 同时修改同一核心服务。

推荐执行方式：

1. 主协调 agent 先完成 `SA-00`，恢复可用的 CI 基线。
2. 按依赖图分派单一工作包；每个 subagent 只接收自己章节、全局规则和必要文件。
3. 每个工作包先添加失败的回归测试，再修改实现。
4. 每个工作包使用独立分支和独立 PR；不要把多个 P0 问题揉进一个大提交。
5. 主协调 agent 依据本文的验收矩阵合并，而不是依据“代码看起来合理”合并。
6. 若实现与文档冲突，默认先让实现对齐现有文档；只有在文档明显不正确且经主协调 agent 确认后，才修改规则文档。

本文中的“必须”属于合并门槛；“建议”可以在不阻塞当前里程碑的情况下后移。

---

## 2. 不可破坏的产品与计算边界

所有 subagent 在开始编码前必须理解并遵守以下约束：

### 2.1 事实源

权威事实只有：

- Instrument / ETF identity
- Market quote、daily market price、fund NAV、split event
- Transaction ledger
- Investment plan 和冻结后的 plan cycle intent

Portfolio、holding、allocation、P/L、XIRR、contribution progress 和 recommendation 都是可重建投影，不允许新增“手工修改持仓”的接口或数据库事实表。

### 2.2 v1/v1.1 继续不做

本阶段不引入：

- 券商 API、真实下单、自动交易
- 个股、期权、加密货币
- 新闻、AI 投资建议、价格预测、技术指标
- WebSocket 实时行情、Level 2
- 多用户 SaaS、注册、密码重置、JWT
- 多币种、税务、回测、模拟盘

看到这些需求时，subagent 必须停止并报告“超出当前产品边界”，不能顺手实现。

### 2.3 金融正确性

- Java 金融值使用 `BigDecimal`；数据库使用 `NUMERIC`。
- 除 `3Y CAGR` 文档允许的孤立指数运算边界外，不使用 `double` 承载金融值。
- 历史持仓必须按历史日期重放交易和拆股，禁止“当前持仓 × 历史价格”。
- 绩效指标使用 provider-adjusted close；若 adjusted close 缺失，返回 `null` 和明确状态，不能用 raw close 冒充。
- 市值使用 raw market close；NAV 不能用 market price 填充。
- 缺失价格不能用未来价格、当前价格或零补齐。
- BUY/SELL fee 只能计入一次；FIFO、realized P/L、net invested 和 XIRR 必须遵循 [`calculations.md`](./calculations.md)。
- Recommendation 是 contribution-first，不卖出超配资产。

### 2.4 故障语义

- `FRESH`、`STALE`、`PARTIAL`、`UNAVAILABLE`、`INSUFFICIENT_HISTORY` 必须真实表达数据状态。
- provider 故障可以展示带时间戳的旧真实数据，但不能展示合成的用户资产数据。
- API 网络不可达时，live 模式必须显示错误/不可用；不能自动替换为 fixture portfolio、fixture transaction 或 fixture plan。
- provider 测试使用 mock server 或脱敏 fixture，不能依赖 live Yahoo/Twelve Data/Alpha Vantage。

### 2.5 数据库、安全和部署

- 所有 schema 变更使用新的 Flyway migration；禁止修改已发布 migration。
- 生产继续使用 `ddl-auto=validate`。
- 登录继续使用 session + CSRF；禁止改成 JWT。
- provider key、数据库密码、password hash 不进入浏览器、API 响应、日志、Git 或测试快照。
- PostgreSQL 版本和卷兼容规则继续遵循 [`architecture.md`](./architecture.md)。

---

## 3. 当前代码现状判断

### 3.1 已经具备的主干能力

当前仓库不是空壳，以下纵向链路已经存在：

- React/Vite 页面：Dashboard、Plan、ETFs、Transactions、Settings、Login。
- Spring Boot 模块：instrument、marketdata、transaction、portfolio、plan、settings、security。
- Yahoo、Twelve Data、Alpha Vantage provider adapter 和 provider priority/fallback。
- 交易增删改、CSV preview/commit、global import fingerprint。
- split-aware FIFO、realized/unrealized P/L、XIRR、allocation、contribution-first recommendation。
- session、CSRF、login throttling、Problem Details。
- Flyway、PostgreSQL 18.6 schema integration test、Docker Compose、Caddy、备份与恢复脚本。
- 较好的金融计算单元测试和 provider adapter 测试。

因此下一阶段不应进行“全量重写”或“先换框架”。

### 3.2 已确认的问题

| ID | 优先级 | 已确认现象 | 影响 |
| --- | --- | --- | --- |
| F-01 | P0 | GitHub Actions 的 `Run web lint when defined` 判断错误；npm 10 对缺失脚本返回 `{}`，CI 仍执行不存在的 `npm run lint`。 | Web tests/build 被跳过，`main` 持续红灯。 |
| F-02 | P0 | `apps/web/src/lib/api.ts` 在普通 live 请求发生网络错误时自动调用 fixture fallback。 | API 宕机时可能把模拟持仓、计划和交易显示成用户真实资产。即使标记 `STALE/FIXTURE`，也不符合个人金融终端的真实性边界。 |
| F-03 | P0 | `MarketDataService.sync` 在 `bar.adjustedClose() == null` 时写入 raw `close`。 | 未复权价格会被持久化为“复权价格”，长期收益、YTD、CAGR、drawdown 可能看似完整但不真实。 |
| F-04 | P0 | `PortfolioService.history` 只要查询范围内存在任意 snapshot，就直接返回这些 snapshot。 | 只有“今天”快照时，历史图可能只有一个点；缓存部分覆盖时不会补算缺失日期。 |
| F-05 | P0 | Transaction create/update/delete/CSV commit 只调用 `rebuildTodaySnapshot()`，未使受历史交易影响的旧 snapshot 失效。 | 回填或编辑历史交易后，旧历史曲线可能继续使用过期投影。 |
| F-06 | P1 | `refreshUpcomingCycles` 依赖持久化的 cycle status 判断是否可重写；status 只有读取时才刷新。 | 已进入执行窗口但尚未读取的 cycle 仍可能以 `UPCOMING` 状态被新 plan allocation 覆盖，破坏冻结历史意图。 |
| F-07 | P1 | 当前没有真实浏览器贯穿 session/CSRF、PostgreSQL、API、前端和 mock market provider 的端到端门禁。 | 单元测试全部通过时，核心用户旅程仍可能在集成层断裂。 |

### 3.3 已确认但暂不作为 P0 的债务

| ID | 建议优先级 | 现状 | 处理原则 |
| --- | --- | --- | --- |
| D-01 | P2 | `TransactionService.list` 先加载全部交易再在内存过滤。 | 单用户小账本可暂用；在引入分页前先保留兼容 API。 |
| D-02 | P2 | `nextLedgerOrder()` 使用 JVM `synchronized + max + 1`，数据库有唯一索引。 | 单实例 v1 通常可用；若未来允许多 API 实例，应改成数据库 sequence/原子分配。 |
| D-03 | P2 | Dashboard 一次请求多次重放 ledger：summary、holdings、allocation 分别计算。 | 先修正确性，再合并为一次 projection context。 |
| D-04 | P2 | `apps/web/src/lib/api.ts` 和 `fixtures.ts` 体积大、职责混合。 | 先完成 live/demo 边界，再做模块拆分，避免同时重构和改行为。 |
| D-05 | P2 | CI 仅语法检查备份/恢复脚本，没有真正 dump/restore 验证。 | v1.1 至少增加一个 PostgreSQL 18.6 恢复 smoke test。 |
| D-06 | P2 | 多处界面文案仍硬编码英文，且无完整 accessibility smoke。 | 不阻塞 P0，但应在前端质量工作包中收敛。 |

---

## 4. v1.1 目标和完成定义

### 4.1 目标陈述

用户在任何时候看到的资产、收益和计划状态都必须满足：

1. 来自真实 ledger 和可追溯 market data；
2. 缺失时明确缺失，不由 fixture/raw-close/current-price 补造；
3. 历史交易或行情变化后可以确定性重建；
4. CI 可以重复证明核心行为；
5. 备份可以在 PostgreSQL 18.6 中恢复并通过最小业务校验。

### 4.2 v1.1 Release Gate

P0 全部完成后才允许标记 v1.1 候选版本：

- [ ] `main` 的 Web/API/PostgreSQL/Compose/Repository CI 全绿，且 Web tests/build 实际执行而非 skipped。
- [ ] live 模式网络故障不返回任何 fixture financial data。
- [ ] demo 模式必须显式启用，并在整个 UI 中持续标记。
- [ ] provider 缺失 adjusted close 时数据库保持 `NULL`，相关指标返回 `PARTIAL`/`INSUFFICIENT_HISTORY`。
- [ ] 历史接口可以合并已有 snapshot 与重算结果，不因单个 snapshot 提前返回。
- [ ] 历史交易增删改会使受影响日期及之后的 snapshot 失效并可重建。
- [ ] 回归测试覆盖上述五项。
- [ ] 至少一条真实浏览器 smoke 通过：登录 → 跟踪 ETF → 同步 mock 行情 → 创建计划 → 录入 BUY → Dashboard 显示 ledger-derived holding。
- [ ] provider/API 故障 smoke 明确证明“无伪造数据”。
- [ ] 文档、API 行为和实现没有新的冲突。

P1 完成后，才建议部署为长期运行版本：

- [ ] current/past plan cycle 冻结规则经过边界测试。
- [ ] PostgreSQL backup/restore smoke 通过。
- [ ] session/CSRF 安全集成测试通过。
- [ ] 发布 runbook 包含部署、回滚、数据修复和恢复步骤。

---

## 5. 阶段与依赖关系

```text
Phase 0: 可验证基线
  SA-00 CI baseline
        |
        +---------------------+
        |                     |
Phase 1: 数据真实性           |
  SA-01 live/demo boundary    SA-02 adjusted-close integrity
        |                     |
        +----------+----------+
                   |
  SA-03 snapshot/history rebuildability
                   |
  SA-04 plan-cycle temporal integrity
                   |
Phase 2: 集成证明
  SA-06 browser/API acceptance harness
                   |
Phase 3: 生产化与收敛
  SA-07 frontend maintainability
  SA-08 operations/security/recovery
  SA-09 performance/observability
                   |
  SA-10 release integration
```

并行规则：

- `SA-01` 与 `SA-02` 可在 `SA-00` 后并行，文件几乎不重叠。
- `SA-03` 会触及 portfolio、transaction、market-data 的失效入口，应单独串行，避免与 `SA-02` 同时修改 `MarketDataService.java`。
- `SA-04` 可与 `SA-03` 大部分并行，但若两者都修改 `TransactionService.java`，由主协调 agent 先约定接口或串行合并。
- `SA-07` 必须在 `SA-01` 合并后执行，防止重复拆分 `api.ts`。
- `SA-09` 必须在 `SA-03` 合并后执行，不能在错误的 snapshot 策略上优化。
- `SA-06` 的最终 smoke 必须基于 P0 合并后的 `main`。

---

## 6. 所有 subagent 的统一执行规则

### 6.1 开工前

每个 subagent 必须先做以下动作并在回复中列出结果：

1. 阅读本工作包指定的“先读文件”。
2. 写出当前行为，不能直接写“将优化”。
3. 写出一个最小失败测试，说明它为什么在当前代码上失败。
4. 确认不跨越 v1/v1.1 非目标。
5. 列出预期修改文件；若预计超过 12 个文件，先停止并拆分。

### 6.2 编码规则

- 一个 PR 只解决一个工作包。
- 不做无关格式化、依赖升级、命名重构或 UI redesign。
- 不删除已有测试来让构建通过。
- 不使用 `continue-on-error`、跳过测试或扩大异常捕获掩盖错误。
- 不把 provider/数据库真实凭据写入 fixture、测试或日志。
- 不引入 live 网络测试。
- 修改 API contract 时必须同时修改 `docs/api.md`、TypeScript types 和 contract test；没有主协调 agent 同意时不要改 envelope。
- 修改 schema 时新增 migration，并运行 `postgresTest`。
- 金融逻辑改动必须使用明确的小数断言，不能只断言非空。
- 所有时间边界测试使用固定 `Clock`，不能依赖机器当前时间。

### 6.3 每个 PR 必须报告

```text
工作包：SA-XX
分支：agent/xx-short-name
提交：<sha list>

已验证的旧行为：
- ...

变更：
- ...

新增/修改测试：
- test name -> protects what

执行命令：
- command -> PASS/FAIL

未解决风险：
- ...

是否改变 API/schema/config：yes/no
若 yes：迁移/兼容/回滚说明
```

### 6.4 停止条件

出现以下任一情况时，subagent 不应继续猜测：

- 需要新增产品事实源或改变交易/收益定义；
- 需要删除或重写既有 migration；
- 需要把 fixture 带入生产构建；
- 需要通过 raw close/current price/zero 补齐缺失数据；
- 需要引入券商、AI、实时交易等非目标；
- 同一个缺陷有两个相互冲突的数据模型方案；
- 发现现有生产数据库可能需要破坏性数据清理，但无法证明可恢复。

此时只提交调查结果和最小复现，不提交猜测性修复。

---

# 7. Subagent 工作包

## SA-00 — 恢复可信 CI 基线

**优先级：P0，必须最先完成**  
**建议分支：`agent/00-ci-baseline`**  
**允许并行：否**

### 先读文件

- `.github/workflows/ci.yml`
- `apps/web/package.json`
- `apps/web/package-lock.json`
- `docs/agent-handoff.md` 中“测试/CI”段落

### 已知根因

当前步骤：

```bash
if [ "$(npm pkg get scripts.lint)" != "null" ]; then
  npm run lint
fi
```

在当前 npm 版本中，缺失 `scripts.lint` 返回 `{}` 而不是 `null`，所以 CI 错误执行不存在的 lint script。后续 Web tests/build 因 step failure 被 skipped。

### 目标

恢复绿色基线，且不能通过跳过 tests/build、允许失败或删除质量检查实现。

### 逐步任务

1. 在本地或一次受控 shell 中确认：
   - `npm pkg get scripts.lint` 的实际输出；
   - `npm ci`、`npm audit --omit=dev` 当前可通过。
2. 把 lint 存在性检测改为读取 `package.json` 的确定性 Node 检查，例如：

   ```bash
   if node -e "const p=require('./package.json'); process.exit(p.scripts?.lint ? 0 : 1)"; then
     npm run lint
   fi
   ```

   可使用等价方案，但不能依赖 npm 对缺失字段的字符串格式。
3. 在 Web job 中增加显式 `npm run typecheck` step；不要只依赖 build 间接运行 TypeScript。
4. 保留 `npm test -- --run` 和 `npm run build`，确认 Actions 日志中它们实际运行。
5. 不在本工作包引入 ESLint 依赖；真正的 lint 规则由 `SA-07` 单独完成。
6. 更新 handoff 或本文件中的 CI 状态时，只记录实际 Actions 结果。

### 预计修改文件

- `.github/workflows/ci.yml`

### 必须执行

```bash
cd apps/web
npm ci
npm audit --omit=dev
npm run typecheck
npm test -- --run
npm run build

cd ../api
./gradlew test build --no-daemon
./gradlew postgresTest --no-daemon

cd ../..
git diff --check
```

### 验收标准

- `Run web lint when defined` 在没有 lint script 时成功跳过。
- Web tests 和 build 不再显示 skipped。
- 所有现有 CI jobs 绿色。
- 未新增 `continue-on-error` 或条件性跳过 tests/build。

### 回滚

只需回滚 workflow commit；本工作包不改变应用代码或数据。

---

## SA-01 — 拆开 live 与 demo，禁止故障时自动展示 fixture 资产

**优先级：P0**  
**依赖：SA-00**  
**建议分支：`agent/01-truthful-runtime-mode`**  
**允许并行：可与 SA-02 并行**

### 先读文件

- `apps/web/src/lib/api.ts`
- `apps/web/src/lib/api.test.ts`
- `apps/web/src/lib/fixtures.ts`
- `apps/web/src/lib/fixtures.test.ts`
- `apps/web/src/components/DataState.tsx`
- `apps/web/src/components/AppShell.tsx`
- `apps/web/src/pages/DashboardPage.tsx`
- `apps/web/src/vite-env.d.ts`
- `apps/web/Dockerfile`

### 目标

建立两个明确模式：

- `live`：默认模式；所有资产数据只来自 API。网络失败抛出 `ApiError`，页面显示 error/unavailable。
- `demo`：只有显式构建/启动配置才能进入；使用 fixture，并在 AppShell 持续显示不可忽略的“Demo data”标记。

禁止存在“先请求真实 API，失败后自动展示 fixture”的第三种模式。

### 推荐设计

优先使用一个明确变量，例如：

```text
VITE_APP_MODE=live | demo
```

若为减少改动继续使用 `VITE_FORCE_FIXTURES`，也必须满足：默认 `false`、生产 Dockerfile 不设置为 true、live network failure 不调用 fallback、demo UI 有持续标记。

### 逐步任务

1. 在 `api.test.ts` 先添加回归测试：
   - `fetch` 抛网络错误；
   - 调用 `api.getDashboard()`；
   - 期望 promise reject 为 `ApiError(status=0)`；
   - 断言结果中不存在 fixture holding/plan/transaction。
2. 添加 demo 模式测试：显式 demo 配置下可以得到 fixture，但 meta/source 必须明确为 `FIXTURE`。
3. 重构 `read()`：
   - live 模式只 normalize API response；network error 继续抛出；
   - demo 模式直接调用 demo adapter；
   - 删除 network-error 自动 `fallback()` 分支和“Showing local demo data”行为。
4. 检查所有 API 方法，确保 live mutation 从不在本地成功：create/update/delete/import/login/logout 都必须依赖真实 API。
5. 在 AppShell 增加 demo-wide banner/watermark；不能只在某个页面的 `DataStateBanner` 中出现。
6. 页面在 live API 网络错误时使用已有 `ErrorState`，保留 retry；不要展示旧 fixture 数字。
7. `apps/web/Dockerfile` 明确保持 live 默认；若增加 build arg，非法值应使 build 失败。
8. 更新 README/部署配置，说明 demo 只用于本地展示，不能用于真实资产环境。
9. 保留 fixture 测试价值，但将其定位为 demo adapter/test data，不是 runtime resilience。

### 预计修改文件

- `apps/web/src/lib/api.ts`
- `apps/web/src/lib/api.test.ts`
- `apps/web/src/lib/fixtures.ts`（仅必要改动）
- `apps/web/src/components/AppShell.tsx`
- `apps/web/src/vite-env.d.ts`
- `apps/web/Dockerfile`（若增加 guard）
- `README.md` 或 `docs/architecture.md`

### 禁止事项

- 不允许“fixture + 红色 warning”作为 live fallback。
- 不允许把 demo 数据写入真实 API/数据库。
- 不允许根据 `NODE_ENV=production` 猜模式；模式必须显式且默认 live。
- 不允许 catch 所有 API error 后返回空数组，因为空数组会把故障伪装成“用户没有数据”。

### 必须测试

- live + network down → reject/error state；无 fixture。
- live + HTTP 401/403/500 → 保留结构化错误，不 fallback。
- demo → fixture 可用且全局明确标识。
- live mutation + network down → 操作失败，不修改本地 fixture。
- logout 后 CSRF cache 清理仍然成立。

### 验收标准

- 搜索生产调用链，不存在网络异常触发 `getFixture*` 的路径。
- 生产 Docker build 默认 live。
- Dashboard、Plan、Transactions、ETFs、Settings 的 API failure 都不会显示模拟业务数据。
- demo 模式视觉上不可被误认为真实账户。

### 回滚

前端纯行为变更；回滚 commit 即可。不要回滚到自动 fixture fallback 后部署为真实账户环境。

---

## SA-02 — 修复 adjusted-close 真实性和历史数据回补策略

**优先级：P0（写入真实性）+ P1（既有数据回补）**  
**依赖：SA-00**  
**建议分支：`agent/02-adjusted-close-integrity`**  
**允许并行：可与 SA-01 并行；与 SA-03 修改 MarketDataService 时需串行**

### 先读文件

- `docs/market-data.md`
- `docs/calculations.md`
- `apps/api/src/main/java/com/dca/terminal/marketdata/MarketDataService.java`
- `MarketMetricsCalculator.java`
- `YahooFinanceProvider.java`
- `TwelveDataProvider.java`
- `MarketDataEntities.java`
- `PriceDailyRepository.java`
- `apps/api/src/test/java/com/dca/terminal/marketdata/MarketDataServiceCorrectnessTest.java`
- `MarketMetricsCalculatorTest.java`
- `TwelveDataProviderTest.java`
- `YahooFinanceProviderTest.java`

### 已确认的错误

当前同步代码：

```java
entity.setAdjustedClose(bar.adjustedClose() == null ? bar.close() : bar.adjustedClose());
```

这与文档“adjusted close 缺失时保持 nullable”冲突。

### 目标

- provider 未提供 adjusted close 时，数据库存 `NULL`。
- 依赖复权价的指标不得 silently 使用 raw close。
- 对已经部署的数据制定可审计回补方案，不用 `adjusted_close == close` 这类不可靠启发式判断。

### 逐步任务 A：阻止继续写入错误值

1. 在 `MarketDataServiceCorrectnessTest` 使用 `ArgumentCaptor<PriceDailyEntity>` 添加测试：provider `PriceBar.adjustedClose=null` 时，保存实体的 `adjustedClose` 为 `null`。
2. 修改同步赋值，保留 provider 原值。
3. 检查 provider adapter：
   - raw OHLC 与 adjusted series 分离；
   - adjusted endpoint 缺行时返回 `null`；
   - 不在 provider adapter 内用 raw close 补齐。
4. 检查 `MarketMetricsCalculator`：1M/3M/YTD/1Y/3Y CAGR/current drawdown/max drawdown 需要复权端点时，任一端点缺失应返回 `null` 并设置 `PARTIAL` 或 `INSUFFICIENT_HISTORY`。
5. 52W high/low 继续使用 raw high/low，不受此变更影响。
6. API/前端保持 `null -> --`，不能格式化成 0%。

### 逐步任务 B：处理既有可能受污染的数据

先确认实际部署是否已有 `market_price_daily` 数据。不得直接假设数据库为空。

推荐安全策略：

1. 不使用 `adjusted_close = close` 判断一行是否被污染，因为真实复权价在无分红/拆股区间也可能等于 raw close。
2. 增加显式 full-history resync 能力，复用现有 provider SPI，范围仍受五年上限和速率限制保护。
3. full resync 必须幂等，按 `(instrument, trade_date, source)` upsert。
4. 在回补完成前，无法证明来源的复权指标应降级而不是继续宣称 `FRESH`。
5. 若生产数据库尚未承载用户数据，可只修代码并在部署 runbook 记录“首次部署无需数据迁移”。
6. 若已有数据，需要提交一份具体数据修复说明：备份、影响行数查询、full resync、指标抽查、失败回滚。未经主协调 agent 确认，不提交破坏性 `UPDATE all rows` migration。

### 可选的增量改进（同 PR 过大时拆分）

- 对最近若干日使用有界 overlap sync，以吸收 provider 的迟到修订。
- 记录 sync 的 requested range、returned range、缺失 adjusted count。
- 对内部缺口做审计日志；不要在没有可靠交易日历时自动判定所有节假日为缺口。

### 预计修改文件

- `MarketDataService.java`
- `MarketMetricsCalculator.java`（若当前缺失处理不完整）
- 对应测试
- 可能新增 full-resync DTO/controller/service 方法
- `docs/market-data.md`、`docs/api.md`（仅新增能力时）

### 必须测试

- provider adjusted close null → DB null。
- raw close 存在但 adjusted close null → 长期 return/drawdown null，不是 0 或 raw-return。
- YTD 前一年最后交易日 adjusted 缺失 → null + status。
- 52W high/low 仍按 raw high/low。
- full resync 重复执行不新增重复行。
- provider full resync 失败时保留旧行并明确状态，不清空可用历史。

### 验收标准

- 代码中不存在把 raw close 赋给 adjusted close 的 fallback。
- 单测明确捕获持久化实体字段，而不是只验证 `save()` 被调用。
- 既有数据是否需要回补有书面结论和可回滚步骤。
- 所有 provider 测试离线可重复。

### 回滚

代码回滚不会恢复已清理数据；若执行数据回补/清理，必须先有数据库备份并在 PR 中列出恢复命令。

---

## SA-03 — 把 portfolio snapshot 变成可失效缓存，并修复历史曲线部分覆盖

**优先级：P0**  
**依赖：SA-00、建议在 SA-02 合并后执行**  
**建议分支：`agent/03-rebuildable-portfolio-history`**  
**允许并行：不要与其他修改 PortfolioService/TransactionService/MarketDataService 的 agent 并行**

### 先读文件

- `docs/architecture.md` 的 Source of truth / projections
- `docs/calculations.md` 的 Portfolio history
- `PortfolioService.java`
- `PortfolioSnapshotEntity.java`
- `PortfolioSnapshotRepository.java`
- `PortfolioController.java`
- `DashboardController.java`
- `TransactionService.java`
- `MarketDataService.java`
- `MarketDataScheduler.java`
- `PortfolioServiceCorrectnessTest.java`
- `TransactionServiceValidationTest.java`
- migrations `V005`、`V012`

### 已确认的两个错误

1. `history()` 中 snapshot list 只要非空就直接返回，未检查 requested range 是否完整。
2. 历史交易 mutation 只重建今天，没有删除/重算受影响日期之后的 snapshot。

### 目标设计

Snapshot 是性能缓存，不是事实源。正确行为：

```text
requested dates
    + existing valid snapshots
    + replay for missing/invalid dates
    -> sorted complete response
    -> optional idempotent snapshot persistence
```

任何影响日期 `d` 及之后投影的事实变化，都必须调用：

```text
invalidateSnapshotsFrom(d)
```

### 逐步任务 A：先补回归测试

至少新增以下测试：

1. requested range 有且只有 today snapshot，仍返回从首个相关交易日到 today 的完整时间序列，而不是一个点。
2. requested range 中间缺一个 snapshot，返回结果补上该日期并保持升序、无重复。
3. backdated BUY 在 8 月 1 日创建后，8 月 1 日及之后 snapshot 被失效；8 月 1 日之前不受影响。
4. update 从 8 月 20 日改到 8 月 5 日，失效起点是 `min(oldDate,newDate)`。
5. delete 使用被删除交易的旧日期作为失效起点。
6. CSV commit 使用本批次最早交易日期作为失效起点，并且整批失败时不失效/不落库。
7. 缺价格的历史点保持 `PARTIAL`，unrealized P/L 为 null；不能把缺失 instrument 当 0 市值损失。
8. snapshot 与 live replay 对同一天得到相同 market value、cost basis、net invested 和 status。

### 逐步任务 B：实现 snapshot coverage 合并

1. 计算 requested start/end 和第一笔相关 transaction date。
2. 把 repository 返回的 snapshot 转为 `Map<LocalDate, Snapshot>`。
3. 不再因 list 非空提前返回。
4. 对缺失日期执行现有 deterministic replay；复用一次 `FifoCalculator.Replay`，不要每天从头重算全部交易。
5. 合并已有 snapshot 和新计算点，按日期升序、去重。
6. 明确周末/非交易日策略：当前历史是 calendar-day replay + previous valid EOD carry-forward，继续遵循文档并标记 `PARTIAL`；不要在此 PR 改成只返回交易日。
7. 只有在事务安全且不会隐藏失败时才持久化新点；若先选择 read-through 不落库，也可接受，但要记录后续性能影响。

### 逐步任务 C：实现统一失效入口

建议新增一个小而单一的组件，例如：

```text
PortfolioSnapshotInvalidator
- invalidateFrom(LocalDate date)
- invalidateAll()
```

该组件只依赖 snapshot repository，避免 MarketDataService 与完整 PortfolioService 相互循环依赖。

Repository 增加明确删除方法，例如：

```text
deleteAllBySnapshotDateGreaterThanEqual(date)
```

调用点：

- Transaction create：new trade date。
- Transaction update：min(old trade date, new trade date)。
- Transaction delete：old trade date。
- CSV commit：batch min trade date。
- Daily price/split 的历史 upsert：earliest changed date。
- provider priority 变化：如果同日 canonical row 可能变化，保守 `invalidateAll()` 或记录需要重建的范围。

### 逐步任务 D：重建当前日

- mutation 成功、ledger validation 成功、snapshot invalidation 成功后，再重建 today。
- 任一步失败应回滚同一数据库事务。
- provider scheduler 的单个 instrument 失败不应删除其他 instrument 的真实历史；失效范围只基于实际成功变更。

### Schema 决策

优先不新增 schema：删除受影响 snapshot 并按需重算已经足够正确。

只有在需要记录 `calculation_version`/`rebuilt_at` 时才新增 migration；不能为了“以后可能用”提前加列。

### 预计修改文件

- `PortfolioService.java`
- `PortfolioSnapshotRepository.java`
- 可能新增 `PortfolioSnapshotInvalidator.java`
- `TransactionService.java`
- `MarketDataService.java`/`MarketDataScheduler.java` 的小型调用改动
- portfolio/transaction/marketdata tests
- migration（仅确有 schema 需求）

### 性能约束

- 不允许为每个历史日期重新查询全部 transactions/prices。
- 同一次 history 请求尽量一次加载 transaction、split 和 price rows。
- 先正确后优化；本工作包不做 dashboard 全面缓存重构。

### 验收标准

- 一个 existing snapshot 不会截断一年历史。
- 历史交易编辑后不可能继续返回旧 snapshot。
- snapshot 可全部删除后从事实源重建出相同结果。
- API failure/price missing 仍返回诚实 status。
- PostgreSQL integration test 通过。

### 回滚

失效操作只删除可重建缓存。回滚代码后可通过重新运行 snapshot rebuild 恢复；禁止删除 transaction/price facts。

---

## SA-04 — 固化 plan cycle 的时间边界和历史意图

**优先级：P1，建议纳入 v1.1**  
**依赖：SA-00；若修改 TransactionService，排在 SA-03 后**  
**建议分支：`agent/04-plan-cycle-invariants`**

### 先读文件

- `docs/calculations.md` 的 Plan cycles / Recommendation
- `PlanService.java`
- `InvestmentPlanCycleEntity.java`
- `InvestmentPlanCycleAssetEntity.java`
- `PlanServiceCorrectnessTest.java`
- `PlanServiceTest.java`
- `TransactionService.java`
- `TransactionDtos.java`

### 风险说明

`refreshUpcomingCycles` 依据持久化 `cycle.status == UPCOMING` 决定是否用新 plan 重写 frozen asset allocation；但 status 只在 cycles 被读取时刷新。时间进入执行窗口后，如果用户从未打开 cycle 页面，数据库仍可能保留 `UPCOMING`，随后 plan update 会重写本应冻结的当前 cycle。

### 目标

Cycle 是否可修改必须由不可歧义的事实决定，而不是依赖可能过期的 projection status。

推荐规则：

- 执行窗口尚未开始、没有 linked transaction 的 future cycle：可随 plan update 刷新。
- 执行窗口已开始、已结束或已有 linked transaction 的 cycle：planned amount 和 cycle assets 冻结。
- status 是根据 today、window、executed amount 计算的 projection；存储值可更新，但不能作为唯一的冻结判断。

### 逐步任务

1. 用固定 `Clock` 添加测试：
   - plan 在月初创建，cycle persisted `UPCOMING`；
   - clock 前进到执行窗口内但未调用 `cycles()`；
   - update plan；
   - current cycle 的 frozen weights/amount 不变，未来 cycle 更新。
2. 添加边界测试：窗口前一天、开始日、结束日、结束后、二月、31 日被截断的月份。
3. 将“cycle 是否可刷新”提取成纯函数，例如 `isMutableFutureCycle(cycle, today)`，同时检查 linked transactions。
4. `refreshUpcomingCycles` 使用该函数，而不是只看 persisted status。
5. 检查 `refreshAndResponse` 的状态优先级是否符合文档；未来交易不应使窗口前 cycle 提前 PARTIAL/COMPLETED。
6. 对“是否允许 future-dated transaction”作明确产品决定：
   - 推荐真实 ledger 拒绝 `tradeDate > today`，返回稳定 error code；
   - 若保留未来录入，则 cycle status 计算必须忽略尚未发生的 transaction，且文档要明确。
7. 不改变 contribution-first allocation 算法，除非新增测试证明现有结果违反文档。

### 预计修改文件

- `PlanService.java`
- `PlanServiceCorrectnessTest.java`
- 可能 `TransactionService.java` 和对应测试
- `docs/calculations.md`/`docs/api.md`（仅 future-date 规则此前未定义时）

### 必须测试

- plan update 在窗口前只更新 future mutable cycles。
- plan update 在窗口内不改变 current cycle frozen assets。
- linked BUY 后即使 status 字段异常也不可重写。
- previous cycle 永不被后续 plan edit 改写。
- recommendation suggestions sum 精确等于 contribution。
- missing/stale price status 保持正确。

### 验收标准

- Frozen intent 不依赖“用户是否访问过 cycle endpoint”。
- 所有时间测试使用固定 `Clock`。
- 不重写历史 cycle rows 来迁就新计划。

### 回滚

纯服务规则变更通常可回滚；若增加 future-date validation，回滚前检查是否已有通过新规则录入的数据。

---

## SA-05 — Transaction ledger 和 CSV 的第二阶段加固

**优先级：P2，不阻塞 P0**  
**依赖：SA-03、SA-04 的 TransactionService 改动先合并**  
**建议分支：`agent/05-transaction-hardening`**

### 先读文件

- `TransactionService.java`
- `TransactionController.java`
- `TransactionRepository.java`
- `TransactionEntity.java`
- `TransactionDtos.java`
- `FifoCalculator.java`
- migrations `V003`、`V007`、`V008`
- transaction tests
- `apps/web/src/pages/TransactionsPage.tsx`

### 目标

在不改变交易事实定义的情况下，提高并发安全、输入边界和大账本可用性。

### 子任务 5A：ledger order 原子分配

当前 `synchronized + max + 1` 只在单 JVM 内串行；多实例或异常重试可能撞数据库 unique index。

1. 先添加并发/冲突复现测试；若当前产品明确永远单实例，可记录风险并推迟。
2. 推荐使用 PostgreSQL sequence 或其他数据库原子机制。
3. 新增 Flyway migration；不能删除 `uq_transaction_ledger_order`。
4. JPA insert 后必须得到实际 ledger order，FIFO tie-break 继续确定性。
5. 验证旧行顺序不变。

### 子任务 5B：CSV 资源边界

1. 配置并测试最大上传大小、最大 row count 和最大单字段长度。
2. preview/commit 对同一组 rows 继续使用 canonical fingerprint。
3. commit 任一 row 失败时整批回滚。
4. duplicate error 返回 row number，避免只返回拼接字符串。
5. 不在日志打印整行 notes 或用户完整 CSV。

### 子任务 5C：列表查询

1. 把 symbol/from/to 过滤移入 repository query，避免先加载全部交易。
2. 暂不改变默认 bare array contract。
3. 只有在 UI/数据量证明需要时，再设计可选 pagination；需同时更新 API docs 和前端。

### 验收标准

- 同日多交易仍按稳定 ledger order 重放。
- CSV oversized/too-many-rows 有稳定 4xx code。
- duplicate import 继续在数据库约束层有最后防线。
- 小账本 API contract 不被破坏。

---

## SA-06 — 建立端到端和契约验收层

**优先级：P1，P0 合并后启动**  
**依赖：SA-00 至 SA-04**  
**建议分支：`agent/06-e2e-acceptance`**

### 先读文件

- 根 README 的部署与验收命令
- `deploy/docker-compose.yml`
- `deploy/.env.example`
- `apps/web/src/App.tsx`
- auth/session/CSRF controller 和 security config
- instrument/transaction/plan/dashboard controllers
- Yahoo provider 的 base-url 配置
- 当前所有 frontend page tests

### 目标

增加少量但高价值的真实集成测试，证明浏览器、反向代理、session/CSRF、API、PostgreSQL 和 market-data adapter 可以共同工作。

### 测试环境设计

不得调用 live provider。推荐：

1. 新增 `deploy/docker-compose.e2e.yml` 或独立 test compose override。
2. 启动 PostgreSQL 18.6、API、Web，以及一个本地 mock HTTP server。
3. API 的 `YAHOO_BASE_URL`/等价设置指向 mock server。
4. mock server 提供经过最小化的 search、daily chart、quote 响应。
5. 使用 Playwright 或同等级浏览器工具；版本锁定到 package-lock。
6. e2e 数据库使用独立 volume，测试后清理；绝不连接生产 volume。

### Blocking smoke 场景

#### E2E-01 核心旅程

1. 打开登录页。
2. 登录并确认 session cookie 建立。
3. 搜索并跟踪 VOO。
4. mock provider 返回五年中的最小可用样本；sync 状态显示 fresh/expected。
5. 创建 monthly plan，权重合计 100%。
6. 新增 BUY 并关联 cycle。
7. Dashboard 显示由该 BUY 计算的 shares、cost basis、market value。
8. 刷新页面，数据仍来自数据库。

#### E2E-02 API 网络故障

1. live mode 正常加载一次。
2. 让 API route 不可达或返回可控 503。
3. 页面显示 error/retry。
4. 页面不得出现 fixture account value、fixture transaction 或 fixture plan。

#### E2E-03 Provider 故障

1. API 和数据库保持可用，mock provider 返回 429/5xx。
2. 已存真实旧价格时显示 `STALE` 和时间戳。
3. 无历史时显示 `UNAVAILABLE/INSUFFICIENT_HISTORY`。
4. 不生成 price history，不返回 HTTP 500 整页崩溃。

### API integration 场景

- CSRF 缺失的 mutation 被拒绝；正确 token 成功。
- CSV duplicate preview + commit 全有或全无。
- backdated transaction 后历史重建，不出现 look-ahead。
- adjusted close 缺失时 metric null/status 正确。
- plan current cycle frozen。

### CI 策略

- PR 默认跑一组 deterministic smoke，控制时长。
- 更完整的恢复/长场景可在 workflow_dispatch 或 nightly；不能成为永不执行的文档命令。
- 失败时上传 Playwright trace/screenshot，但其中不得包含真实秘密。

### 验收标准

- 所有测试离线可重复。
- 测试从用户可见行为验证 ledger-derived 数值，而不是只检查页面存在。
- e2e 不使用 production fixture fallback。
- CI 日志能定位是 browser、API、DB 还是 mock provider 失败。

---

## SA-07 — 前端质量、模块边界和可访问性

**优先级：P2；在 SA-01 后执行**  
**建议分支：`agent/07-web-quality`**

### 先读文件

- `apps/web/src/lib/api.ts`
- `fixtures.ts`
- `types.ts`
- `App.tsx`
- `AppShell.tsx`
- 所有 pages 和已有 tests
- `package.json`、Vite/Vitest config

### 目标

在真实性行为稳定后，降低前端修改风险；不进行视觉重做。

### 子任务 7A：拆分 API 层

建议目标结构：

```text
src/lib/api/
  transport.ts       # fetch, CSRF, ApiError
  normalize.ts       # shared envelopes/status
  auth.ts
  instruments.ts
  portfolio.ts
  plans.ts
  transactions.ts
  settings.ts
  index.ts
src/lib/demo/
  fixtures.ts
  api.ts
```

要求：

- 拆分前后 public `api` 调用兼容。
- transport 不 import demo fixtures。
- normalizer 每个领域有明确 unit test。
- 不在同一 PR 改 API contract。

### 子任务 7B：真实 lint

1. 添加适配 React 19/TypeScript 的 ESLint 配置和固定版本依赖。
2. 增加 `npm run lint`。
3. 规则优先捕获：未处理 promise、无效 hooks、未使用值、危险 any、测试错误。
4. 不一次启用大量只能靠全仓库 disable 通过的风格规则。
5. CI 的 conditional lint 会自动开始执行。

### 子任务 7C：query consistency

- 集中 query keys。
- mutation 成功后只 invalidate 相关 queries。
- logout 清空用户数据 query cache。
- API error 不转成 empty state。

### 子任务 7D：可访问性和 i18n

- 表单 input 有 label/error association。
- icon-only button 有 accessible name。
- modal/dialog focus 可返回。
- holding rows 的 button/table semantics 合理。
- 把明显硬编码英文迁入 i18n；不要求一次翻译所有内部调试文案。
- 加一组 keyboard/accessibility smoke。

### 子任务 7E：bundle

- 先记录 build chunk report，再决定是否拆包。
- charts 已 lazy load 时不要重复优化。
- 不为消除 warning 引入复杂 manualChunks，除非有实测收益。

### 验收标准

- `npm run lint/typecheck/test/build` 全通过。
- live transport 与 demo adapter 无循环依赖。
- 关键 mutation 的 query invalidation 有测试。
- 无新硬编码 financial fallback。

---

## SA-08 — 生产运维、安全和可恢复性

**优先级：P1/P2**  
**依赖：SA-00；CI 文件改动应在 SA-00 后**  
**建议分支：`agent/08-operations-recovery`**

### 先读文件

- `deploy/docker-compose.yml`
- `deploy/Caddyfile`
- `deploy/.env.example`
- `deploy/scripts/backup-postgres.sh`
- `deploy/scripts/restore-postgres.sh`
- `deploy/systemd/*`
- security config、auth controller、security tests
- `docs/architecture.md` 的 PostgreSQL volume compatibility

### 目标

证明“可以恢复”，而不只是“有恢复脚本”。

### 子任务 8A：backup/restore smoke

1. CI 启动 PostgreSQL 18.6 test container。
2. 写入最小但有关联的数据：instrument、price、plan、transaction、snapshot。
3. 执行真实 backup script。
4. 对 gzip 文件执行完整性检查。
5. 创建全新空数据库/volume。
6. 执行 restore script。
7. 验证：Flyway version、transaction count、计划资产、price rows、portfolio 关键总计。
8. 测试只能使用 CI 临时凭据和临时目录。

### 子任务 8B：部署 smoke 脚本

新增一个只读/最小写入的 smoke 流程：

- Caddy `/` 可访问。
- API health minimal。
- 未登录 session 返回 unauthenticated。
- 获取 CSRF、登录、读取 settings/dashboard、logout。
- 不打印 cookie/password/token。

### 子任务 8C：安全回归

- session fixation：登录后 session ID 旋转。
- mutation 无 CSRF 被拒绝。
- cookie flags 在 production profile 正确。
- login throttle 有边界测试。
- Problem Details 不暴露 SQL、provider key、exception stack。
- logout 后旧 CSRF/session 不再可用。

### 子任务 8D：Caddy headers

当前只移除 `Server`。先做浏览器兼容审计，再逐步添加：

- `X-Content-Type-Options: nosniff`
- 合理 `Referrer-Policy`
- 合理 `Permissions-Policy`
- HTTPS 环境 HSTS
- CSP 先 report-only；图表/样式确认后再 enforcement

不要直接提交会阻断 Vite bundle、图表 canvas 或 API 请求的严格 CSP。

### 子任务 8E：运行手册

新增/更新 runbook：

- 首次部署
- 日常升级
- PostgreSQL major upgrade
- full market-history resync
- backup verification
- restore drill
- rollback application image
- 何时不能回滚 schema

### 验收标准

- CI 至少一次真实完成 dump → new DB → restore → verification。
- smoke 输出不包含 secret。
- Caddy header 改动有浏览器 smoke。
- 恢复步骤可以由不了解项目的操作者按顺序执行。

---

## SA-09 — 性能和可观察性（正确性稳定后）

**优先级：P2**  
**依赖：SA-03、SA-06**  
**建议分支：`agent/09-observability-performance`**

### 先读文件

- `DashboardController.java`
- `PortfolioService.java`
- `PlanService.java`
- `MarketDataService.java`
- `MarketDataScheduler.java`
- Spring Actuator/config/logging files

### 目标

减少重复计算并能定位 provider、ledger replay、snapshot rebuild 的失败，不改变金融结果。

### 子任务 9A：Dashboard projection context

当前 dashboard 分别调用 summary/history/holdings/allocation，summary/holdings/allocation 会重复构建 current ledger。

1. 先加结果等价测试。
2. 新增 request-scoped/方法级 projection context，一次加载 current transactions/splits/prices。
3. summary、holdings、allocation 从同一 immutable calculation 生成。
4. history 保持独立 replay/cache 语义。
5. 不把 projection 跨请求长期缓存，避免 mutation 后脏数据。

### 子任务 9B：Micrometer 指标

建议低基数字段：

- provider request count/duration：provider、operation、outcome；不要用 symbol 作为 metric tag。
- sync rows/splits/status。
- snapshot invalidate/rebuild count/duration。
- portfolio replay transaction count/duration。
- CSV rows/invalid/duplicate count。

Symbol 可以留在结构化日志，但要避免 secret/高敏 notes。

### 子任务 9C：容量基线

用生成数据而非真实账户数据：

- 20 instruments
- 10,000 transactions
- 5 years daily prices

记录 dashboard/history/transaction list 的时间和数据库 query 数。只有实测证明瓶颈后再加 index/cache。

### 验收标准

- 优化前后金融结果逐字段相同。
- 指标不暴露 symbol/notes/credentials 作为高基数或敏感 tag。
- 没有为性能重新引入 snapshot 事实源或 stale in-memory portfolio cache。

---

## SA-10 — Release 协调与最终集成

**角色：主协调 agent，不建议交给低档独立模型**  
**依赖：所有拟纳入 release 的工作包**

### 职责

1. 为每个工作包建独立 branch/PR，维护依赖顺序。
2. 在合并前复查文件 ownership，避免并行 agent 相互覆盖。
3. 要求每个 PR 提供失败回归测试和命令证据。
4. 合并后在最新 `main` 重新运行全套命令，而不是拼接各分支结果。
5. 执行第 9 节验收场景。
6. 更新：
   - `docs/agent-handoff.md`
   - `docs/api.md`
   - `docs/architecture.md`
   - `docs/market-data.md`
   - `docs/calculations.md`
   - README/runbook
7. 记录数据迁移和回滚边界。
8. 只有 CI、e2e、restore smoke 和文档均满足目标时才打 release tag。

### 最终合并顺序

建议：

1. SA-00 CI
2. SA-01 live/demo
3. SA-02 adjusted close
4. SA-03 snapshot/history
5. SA-04 plan cycle
6. SA-05 transaction hardening（若纳入本次）
7. SA-07 frontend quality
8. SA-08 operations/security
9. SA-09 performance/observability
10. SA-06 在最终 main 上补齐/修正 acceptance，或在各阶段持续维护后最终重跑
11. 文档与 release commit

---

## 8. 文件所有权与冲突矩阵

| 工作包 | 主要拥有文件 | 易冲突文件 | 协调规则 |
| --- | --- | --- | --- |
| SA-00 | `.github/workflows/ci.yml` | SA-08 CI | SA-00 先合并。 |
| SA-01 | web `api.ts`、fixtures、AppShell | SA-07 | SA-07 后执行。 |
| SA-02 | MarketDataService、metrics/tests | SA-03 | 两者串行合并。 |
| SA-03 | PortfolioService、snapshot repo、失效入口 | TransactionService、MarketDataService | 该工作包独占这些交叉改动。 |
| SA-04 | PlanService/tests | TransactionService | 若加 future-date validation，排在 SA-03 后。 |
| SA-05 | TransactionService/repo/migrations | SA-03/04 | 最后执行。 |
| SA-06 | e2e 新目录/compose override | deploy/CI | 由主协调 agent 解决轻量冲突。 |
| SA-07 | web 模块和 package | SA-01 | 不并行。 |
| SA-08 | deploy/security/CI | SA-00/06 | 基于最新 main。 |
| SA-09 | Dashboard/Portfolio/metrics | SA-03 | 正确性稳定后。 |

---

## 9. 统一验收场景

以下场景是 release 级证据，不应仅靠 mock 一个 service 方法完成。

### A. Live API 不可达

- 前端为 live mode。
- API 连接失败。
- Dashboard/Plan/Transactions/ETFs/Settings 显示 error/retry。
- DOM、network response、downloaded export 中均无 fixture portfolio/transaction/plan。

### B. Provider 不可达但 API 可达

- 已有旧真实 quote/history：显示 `STALE` 和更新时间。
- 完全无数据：显示 `UNAVAILABLE/INSUFFICIENT_HISTORY`。
- ETF identity canonical catalog 只可帮助确认已审核 symbol，不能产生 price/history/NAV。

### C. Adjusted close 缺失

- raw OHLC 正常存储。
- adjusted close 为 null。
- 52W high/low 可计算。
- 依赖 adjusted close 的 return/drawdown 为 null，并有明确 status。

### D. Backdated ledger mutation

- 先创建 8 月 20 日 BUY 并生成 snapshot。
- 后添加/修改为 8 月 5 日 BUY。
- 8 月 5 日前历史不包含该交易；8 月 5 日后按 FIFO/价格重算。
- 不返回旧 snapshot；无 look-ahead。

### E. Partial snapshot coverage

- 仅预置 range 中若干 snapshot。
- history 返回完整有序序列，无重复日期。
- 现有 valid snapshot 可复用，缺失日期由 replay 补齐。

### F. Plan cycle freeze

- cycle persisted status 仍为 UPCOMING。
- fixed clock 已进入执行窗口。
- 修改 plan target weights。
- current cycle frozen weights 不变；真正未来 cycle 更新。

### G. CSV all-or-nothing

- 一批包含 valid、duplicate、oversell 或 invalid cycle row。
- commit 失败时数据库新增 0 行。
- 修正后 commit 成功且重复提交被稳定拒绝。

### H. Backup/restore

- backup 前记录 transaction count、price count、plan weights、portfolio totals。
- restore 到全新 PostgreSQL 18.6。
- Flyway validate 通过，记录值一致。

### I. Auth/CSRF

- 未登录读取受保护资源被拒绝。
- 登录后 session ID 旋转。
- 无 CSRF mutation 被拒绝。
- 有 CSRF mutation 成功。
- logout 后旧 session/token 不可继续写入。

---

## 10. 标准命令矩阵

### Web

```bash
cd apps/web
npm ci
npm audit --omit=dev
npm run typecheck
npm test -- --run
npm run build
# SA-07 合并后：
npm run lint
```

### API

```bash
cd apps/api
chmod +x ./gradlew
./gradlew test build --no-daemon
./gradlew postgresTest --no-daemon
```

### Compose/部署配置

```bash
cp deploy/.env.example deploy/.env
trap 'rm -f deploy/.env' EXIT
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
bash -n deploy/scripts/backup-postgres.sh
bash -n deploy/scripts/restore-postgres.sh
```

### Repository

```bash
git diff --check
git status --short
```

### 规则

- 任一命令失败，报告真实失败；不能删掉命令。
- 无本机 Java 时可以使用项目 Docker/CI 等价环境，但最终 PR 仍需 GitHub Actions 证据。
- 不把 live provider availability 作为 release test 前置条件。

---

## 11. 风险登记表

| 风险 | 概率/影响 | 缓解 | 回滚 |
| --- | --- | --- | --- |
| 既有 adjusted close 无法判断是否来自 provider | 中/高 | 不做等值启发式；显式 full resync；回补前降级状态。 | 数据操作前 backup；保留旧 volume/dump。 |
| Snapshot 重算对长账本变慢 | 中/中 | 一次加载、incremental replay、read-through cache；SA-09 再优化。 | 可关闭持久化回填，只保留按需计算。 |
| 多 agent 修改 TransactionService 冲突 | 高/中 | SA-03 → SA-04 → SA-05 串行；小提交。 | rebase 后人工逐段合并，不接受整文件覆盖。 |
| E2E mock provider 与真实 adapter 不一致 | 中/中 | 使用 adapter 真实 HTTP schema；provider adapter unit tests继续保留。 | E2E fixture 独立版本化。 |
| CSP/安全 header 阻断图表 | 中/中 | report-only、浏览器 smoke、逐项启用。 | 单独 header commit，快速回滚。 |
| Full-history sync 触发 provider rate limit | 中/中 | 分 instrument、有界重试、可续跑、状态透明。 | 保留旧 rows，不先清空。 |
| 数据库 migration 无法回滚 | 低/高 | additive migration、backup/restore drill、发布前审查。 | 应用回滚前检查 schema 兼容；必要时 restore。 |

---

## 12. v1.1 之后的候选 backlog

以下事项不得抢在 P0/P1 前面：

1. Transaction optional pagination 和虚拟列表。
2. PostgreSQL sequence 分配 ledger order（若 SA-05 未纳入）。
3. Dashboard single projection context。
4. Provider health history/运营面板。
5. 更完整的 market-data gap audit。
6. Bundle 分析和按实测拆包。
7. 更丰富的 export（交易、计划、审计字段）。
8. Snapshot calculation version/provenance（仅在确有升级需求时）。
9. 更细的 accessibility/i18n 覆盖。

依然不包含券商、AI、个股、实时交易等产品非目标。

---

## 13. 低档模型可直接使用的工作提示模板

主协调 agent 给 subagent 时，应只替换方括号内容：

```text
你负责 DCA-DASHBOARD 的 [SA-XX 工作包名称]。

只完成本工作包，不做其他重构。先阅读：
- [exact file 1]
- [exact file 2]
- [相关文档章节]

不可破坏规则：
- transaction/price/plan 是事实，portfolio 是投影；
- 不用 fixture/raw close/current price/zero 填补真实数据缺失；
- 金融值用 BigDecimal；
- schema 只通过新 Flyway migration；
- 不调用 live provider；
- 不改变 v1 产品范围。

执行顺序：
1. 用 5-10 句话描述当前行为。
2. 先添加一个在当前 main 上失败的回归测试。
3. 做最小实现修改。
4. 运行本工作包列出的全部命令。
5. 检查 git diff，不提交无关格式化。
6. 按规定模板报告 commit、文件、测试和风险。

若预计修改超过 12 个文件，或需要改变 API/schema/金融定义而任务未明确允许，停止并报告，不要猜。
```

---

## 14. 主协调 agent 最终检查表

### 范围

- [ ] 没有新增非 v1/v1.1 产品功能。
- [ ] 没有新增手工 holdings 事实源。
- [ ] 没有真实下单、券商或 AI 建议代码。

### 数据真实性

- [ ] live network failure 无 fixture。
- [ ] adjusted close 不由 raw close 冒充。
- [ ] NAV 不由 market price 冒充。
- [ ] missing price 不变成 zero loss/zero return。
- [ ] freshness/status/asOf 可见。

### 历史与交易

- [ ] partial snapshots 会补算。
- [ ] backdated mutation 会失效正确范围。
- [ ] FIFO、fee、split、XIRR 测试继续通过。
- [ ] cycle intent 在正确时间冻结。

### 安全和运维

- [ ] session/CSRF tests 通过。
- [ ] secrets 不在 bundle/log/repo。
- [ ] PostgreSQL 18.6 schema test 通过。
- [ ] backup/restore smoke 通过。
- [ ] rollback 与数据修复步骤已记录。

### 工程质量

- [ ] Web lint/typecheck/test/build 通过。
- [ ] API test/build/postgresTest 通过。
- [ ] Compose config 和 shell syntax 通过。
- [ ] Browser smoke 通过。
- [ ] `git diff --check` 通过。
- [ ] 最新 `agent-handoff.md` 与实际实现一致。

---

## 15. 推荐的立即下一步

只做以下三件事，不要同时开始大规模重构：

1. **SA-00：修复 CI lint 检测，让 tests/build 真正执行。**
2. **SA-01：删除 live network-error → fixture fallback。**
3. **SA-02：停止写入伪造 adjusted close，并确定既有数据回补方案。**

三项合并并全绿后，再启动 `SA-03` 的 snapshot/history 修复。这一顺序能最快降低“页面看起来正常但数据不真实”的风险，并给后续 subagent 一个可重复验证的基础。