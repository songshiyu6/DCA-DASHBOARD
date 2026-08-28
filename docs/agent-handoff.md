# DCA Terminal v1 - Agent Handoff

本文是本仓库截至 2026-08-28 的实现交接和对话重点摘要，供后续高级模型 agent 审查使用。

本文不包含任何密码、密码 hash、API key、access token、private key、临时 env 内容或其他 secret。不要从仓库、聊天记录或临时容器输出中寻找并复制这些内容。

## 1. 用户目标

项目名称：DCA Terminal v1

一句话定义：

> 面向长期 ETF 投资者的个人投资终端，跟踪 ETF 行情和历史表现，管理定投计划，记录实际买入，并持续比较计划和执行结果。

核心产品差异：

- ETF 行情
- 我的持仓
- 我的定投计划
- 我的执行纪律

第一版只覆盖：

- 美股 ETF
- 当前行情和历史日线
- 手工定投计划
- 手工交易流水
- 根据交易流水计算持仓
- Portfolio 统计
- 计划、执行和实际配置的比较
- 通过新增资金进行贡献优先再平衡建议

明确不做：

- 真实下单
- 券商 API
- IBKR/Futu 自动同步
- 期权
- 个股研究
- 加密货币
- 新闻
- AI 投资建议
- 预测涨跌
- WebSocket Tick
- Level 2
- 技术指标
- 多用户 SaaS
- 社交
- 模拟交易
- 回测
- 多币种
- 税务计算

## 2. 已确定的产品模型

核心实体：

- Instrument：ETF 身份
- Price：市场历史价格
- Investment Plan：投资计划和目标配置
- Transaction：真实交易流水
- Portfolio：由 Transaction 和 Price 计算出来的结果

事实数据只有：

- Instrument
- Price
- Transaction
- Investment Plan

Portfolio 是计算结果，不是可以被用户直接编辑的主数据。禁止设计类似 POST /portfolio/update-holdings 的事实写入 API。

数据流：

~~~text
Market Data Provider
        |
        v
Instrument -> Prices
        ^
        |
Transaction ----> Portfolio calculation
        ^
        |
Investment Plan
~~~

交易流水是持仓的唯一来源。任何 holdings、cost basis、market value、P/L、allocation 都应该由交易和价格重新计算或由可验证的 snapshot 派生。

## 3. 技术架构

- Frontend：React、TypeScript、Vite
- Backend：Spring Boot 3、Java 21
- Database：PostgreSQL 18.6
- Cache：Caffeine
- Charts：TradingView Lightweight Charts、Apache ECharts
- Deployment：Docker Compose
- Reverse proxy：Caddy
- HTTPS：生产使用 Caddy/Let's Encrypt
- Migration：Flyway
- Security：单用户 Session + CSRF；不使用 JWT

Repository 结构：

~~~text
apps/web
apps/api
deploy
docs
README.md
~~~

当前仓库 remote：

~~~text
git@github.com:songshiyu6/DCA-DASHBOARD.git
~~~

不要替换现有 origin。

## 4. 前端页面

导航固定为：

- Dashboard
- Plan
- ETFs
- Transactions
- Settings

### Dashboard

- Portfolio Market Value
- Total P/L
- Net Invested
- Personal XIRR
- Next DCA
- Portfolio Value vs Net Investment
- Holdings
- Target vs Actual Allocation
- DCA Contribution Progress
- 月度 Cycle 状态

历史 Portfolio Value 必须使用当日以前已经发生的交易和当日价格计算，不得用当前持仓乘历史价格回推，否则会产生 look-ahead bias。

### Plan

- Monthly Budget
- Target Allocation
- Frequency
- Execution Window
- Start Date
- Status
- Plan Assets
- Plan Cycles
- Actual executed amount
- Next contribution recommendation

第一版 UI 只开放 MONTHLY，但数据库保留 WEEKLY、BIWEEKLY、MONTHLY 枚举空间。

### ETFs

- Tracked ETFs
- ETF search
- Add ETF
- ETF detail
- 1D、1W、1M、3M、YTD、1Y、3Y、5Y 图表范围
- Metrics
- Freshness 状态
- Market Price 和 NAV 分开

### Transactions

- Add Transaction
- CSV Preview/Commit
- Filter
- Edit
- Delete
- BUY、SELL、DIVIDEND、FEE
- 碎股

### Settings

- Base Currency
- Primary/Fallback Market Data Provider
- Timezone
- Theme

API keys 只能进入后端/部署环境，不能进入 React env、浏览器 metadata 或响应。

## 5. 数据库重点

主要表：

- instrument
- market_price_daily
- market_quote_latest
- fund_nav_daily
- corporate_action_split
- investment_plan
- investment_plan_asset
- investment_plan_cycle
- transaction
- portfolio_snapshot_daily
- portfolio_snapshot_position（若实现）
- app_setting

关键约束：

- daily price 需要 instrument/date/source 维度的幂等写入。
- canonical source 读取必须按 provider priority 选取，不应让多 provider 同日记录随机竞争。
- NAV 与 market price 必须分离。
- quantity 使用 NUMERIC(20,8) 语义。
- amount/price/fee 使用 NUMERIC/BigDecimal 语义。
- plan asset 的 target weight 总和必须为 100%，允许误差不超过 0.01%。
- transaction 的 plan_cycle_id 可以为空，用于计划外交易。
- CSV 需要稳定 fingerprint，重复导入不能只依赖随机 batch id。
- Flyway 是生产 schema 的唯一变更来源。
- 生产 Hibernate 使用 validate，不能使用 ddl-auto=update。

当前 migration 已到 V013。V012 允许 portfolio_snapshot_daily.unrealized_pl 为空，以便价格不完整时诚实表示 PARTIAL，而不是伪造 -100% 或零值。V013 新增 `transaction_ledger_order_seq` 并为 `investment_transaction.ledger_order` 设置 default；这是前进式变更，不能靠旧应用镜像回滚 schema。

## 6. 行情 Provider

接口设计：

~~~java
interface MarketDataProvider {
    ProviderId id();
    Quote getLatestQuote(Instrument instrument);
    List<PriceBar> getHistoricalPrices(Instrument instrument, LocalDate from, LocalDate to);
    Optional<EtfProfile> getProfile(Instrument instrument);
}
~~~

实现：

- YahooFinanceProvider
- TwelveDataProvider
- AlphaVantageProvider（主要用于 ETF profile）

Provider 不能直接散落在业务代码中。应经过 Provider Registry/MarketDataService，处理：

- timeout
- HTTP 429
- HTTP 5xx
- symbol not found
- provider 返回空
- null/非法价格
- fallback priority
- freshness

Fallback 原则：

- provider 失败可以 fallback
- symbol not found 不应无限 retry
- fallback 失败不能伪造历史价格
- 应返回 UNAVAILABLE、STALE 或 INSUFFICIENT_HISTORY 等可观察状态

canonical ETF catalog 的边界：

- 只提供经过确认的 ETF 身份字段
- 不提供价格
- 不提供历史 bars
- 不提供 NAV
- 不提供 Portfolio 数据
- 不得把任意未知 ticker 伪造成 ETF

当前 canonical catalog 已覆盖：

- VOO
- QQQ
- SCHD
- VTI
- VT
- SGOV
- AVUV

Yahoo Finance 是 unofficial provider，可能被限流。生产建议配置 Twelve Data fallback key，但 key 只能存在后端/部署环境。

## 7. 搜索问题与修复历史

用户最初反馈：

> ETF 代码搜不到，QQQ 和 VOO 都不行。

根因：

1. Yahoo autocomplete 是单点搜索源。
2. 经主机代理访问时，QQQ 和 VOO 均出现 HTTP 429。
3. acceptance API 没有配置 Twelve Data key，fallback 不可用。
4. MarketDataService 把 provider failure 变成空数组。
5. 前端把空数组显示为“没有匹配”，隐藏了数据源故障。

修复：

- 增加严格的 canonical ETF identity catalog。
- Yahoo 搜索保留 provider 结果并和 canonical identity 合并去重。
- provider 失败时，已确认的 QQQ/VOO 等返回身份信息。
- 未知 ticker 在 provider 不可用时返回 503 MARKET_DATA_UNAVAILABLE。
- 前端区分普通空结果和 provider error。
- 增加 Yahoo response contract、QQQ/VOO、429、空结果和前端错误测试。

当时实测：

~~~text
login_http=200
qqq_http=200
voo_http=200
unknown_http=503
~~~

QQQ 和 VOO 均能返回精确 ETF 身份结果。

## 8. VOO 历史数据问题与修复历史

用户随后反馈：

> VOO 添加了，现在无历史数据。

实际根因是多个问题叠加：

1. 旧 acceptance API 镜像仍在运行。
2. Yahoo chart 通过完整浏览器 User-Agent 请求时触发 429。
3. API 容器内的 localhost:7890 指向容器本身，不是宿主代理，导致替换镜像初次请求又出现 403。
4. 添加流程保留了 Instrument 身份，但历史同步失败后没有把失败原因传给 UI。
5. 前端对空历史显示静默空状态，没有 Retry/错误说明。

修复：

- acceptance 临时配置使用 host.docker.internal:7890 连接宿主代理。
- Yahoo chart 使用兼容当前 edge 的最小 User-Agent。
- 已存在但状态为 UNAVAILABLE、STALE 或 INSUFFICIENT_HISTORY 的 instrument，重新 Add 时可以再次尝试历史同步。
- sync response 增加 message。
- ETF detail 空历史显示原因并提供 Retry。
- DataStateBanner 支持 Retry。
- provider 空响应、429、历史已存在等状态有明确语义。
- 增加 chart success/429/empty、add 触发同步、history DTO 和前端空状态测试。

修复后的数据库只读结果：

~~~text
VOO | 1255 rows | 2021-08-27 to 2026-08-27 | adjusted close 1255 rows | FRESH
~~~

修复后的 API 实测结果：

- Login：HTTP 200，authenticated=true
- Search VOO：HTTP 200，精确匹配 1 条
- Search QQQ：HTTP 200，精确匹配 1 条
- Add VOO：HTTP 201，dataStatus=FRESH
- Explicit sync：HTTP 200，barsSaved=0（数据已存在）
- Quote：HTTP 200，价格存在，FRESH，source=YAHOO
- prices?range=5Y：HTTP 200，1255 条
- Metrics：HTTP 200，10 项指标非空，FRESH

不要用 canonical identity 伪造历史 bars。历史数据必须来自真实 provider 或明确标记为 unavailable。

## 9. 金融计算要求

### Today Return

Latest Market Price / Previous Close - 1

### YTD

使用上一年最后一个交易日的 adjusted close：

~~~text
LatestAdjustedClose / PreviousYearLastAdjustedClose - 1
~~~

### 1M/3M/1Y

- 使用 calendar duration 找目标日期。
- 如果目标日无交易，取不晚于目标日期的最近交易日。

### 3Y CAGR

~~~text
(End / Start) ^ (365.2425 / Days) - 1
~~~

### 52W High/Low

- 最近 365 个 calendar days
- 使用 high/low，不要误用 close

### Drawdown

~~~text
LatestAdjustedPrice / HistoricalRunningPeak - 1
~~~

### Max Drawdown

~~~text
runningPeak[t] = max(price[0...t])
drawdown[t] = price[t] / runningPeak[t] - 1
MDD = min(drawdown[t])
~~~

历史 Return 优先使用 adjusted_close；数据库同时保存 close 和 adjusted_close。

### FIFO

- BUY 创建 lot。
- SELL 先消耗最早 lot。
- 同日交易使用确定性的 ledger_order，不能按随机 UUID 排序。
- split 需要跨 provider canonicalize，不能重复应用。
- 碎股和部分卖出保持 BigDecimal 精度。

### P/L

~~~text
Market Value = shares * latest price
Unrealized P/L = market value - remaining cost basis
Total P/L = realized P/L + unrealized P/L + dividends - fees
~~~

### XIRR

现金流：

- BUY：-(quantity * price + fee)
- SELL：+(quantity * price - fee)
- DIVIDEND：净现金流
- 当前持仓市值：今天的正现金流

XIRR 应考虑每一笔资金进入市场的时间。代码和文档已说明幂运算的数值边界、收敛和多根策略。

## 10. Plan 和 Recommendation

Plan 采用 Budget + Target Allocation：

~~~text
Monthly Budget: 1500
VOO: 50%
QQQ: 30%
SCHD: 20%
~~~

PlanCycle 状态：

- UPCOMING
- OPEN
- PARTIAL
- COMPLETED
- SKIPPED

cycle 的 executed amount 只应计入合法关联的 BUY。关联校验需要考虑：

- instrument 是否属于 plan
- transaction type
- cycle period
- execution window
- 月份末日 clamp

当前月 PARTIAL cycle 的下一笔金额：

~~~text
remaining = max(planned_amount - executed_amount, 0)
~~~

Recommendation 使用 contribution-first rebalancing：

~~~text
newPortfolioValue = currentValue + contribution
targetValue[i] = newPortfolioValue * targetWeight[i]
gap[i] = targetValue[i] - currentValue[i]
~~~

- gap > 0：低配，允许买入。
- gap < 0：超配，建议为 0。
- 低配资产按 gap 比例分配 contribution。
- 分母只使用 plan assets，不把计划外 ETF 引入目标配置计算。
- suggestion 总和必须等于投入金额。

空资产计划不应产生无意义分配，返回明确 PARTIAL/empty 状态。

## 11. 安全和部署

单用户认证：

- Session Cookie
- HttpOnly
- Secure 可由环境控制
- SameSite=Lax
- 登录时 rotate/change session id
- CSRF token/header

生产：

- Caddy HTTPS
- Let's Encrypt
- PostgreSQL backup
- 不暴露数据库
- 所有 API key 只在后端
- 不使用 JWT 作为个人站点的必要复杂度

当前局域网 acceptance 预览：

~~~text
http://192.168.2.25:18080/
http://localhost:18080/
~~~

端口：

~~~text
0.0.0.0:18080 -> Caddy:18080
[::]:18080   -> Caddy:18080
~~~

最后已知容器状态：

- Caddy：running
- Web：running/healthy
- API：running/healthy
- PostgreSQL 18.6：running/healthy

临时 acceptance override 在 /tmp 下，不能提交。当前预览使用 HTTP，只适合可信局域网，不得直接暴露公网。

## 12. Git 提交历史

截至本摘要创建时，主分支关键提交：

~~~text
ac88e09 Initial commit
456ae85 feat: deliver dca terminal v1 preview
69446ee fix: harden terminal data correctness boundaries
35de2c6 fix: finalize terminal v1 data boundaries
b310f44 fix: restore ETF historical sync recovery
~~~

最新业务修复提交：

~~~text
b310f44 fix: restore ETF historical sync recovery
~~~

该提交已经 push 到：

~~~text
origin/main
~~~

当前 worktree 的既知状态：

- 业务代码和正常项目文档已提交。
- 用户提供的 AGENTS.md 是未跟踪文件。
- AGENTS.md 未被修改、暂存或提交。
- 不要擅自把它加入业务提交。
- 不要提交 .env、临时 override、凭据、node_modules、dist、build 或其他生成物。

本次用户要求“提交所有代码和 doc，并把对话重点写入一个 md”。本文件就是新增的对话/实现交接文档；代码和已有 docs 已在上面的提交链中，后续应把本文件单独加入新的文档提交。

## 13. CI 当前情况

用户后来看到 GitHub：

> CI: Some jobs were not successful

之前只有 CI / Repository hygiene 单项显示成功，不能推断整个 workflow 全绿。

随后启动了一个 Luna Max CI 诊断 agent，但用户要求“停止手里所有工作”后已被终止。它没有返回失败 job 的最终日志，也没有完成 CI 修复。

因此后续 agent 必须重新检查：

- 最新 workflow run
- 失败 job 名称
- 失败 job 日志
- 对应 commit
- backend Gradle/JDK/Testcontainers/Postgres job
- frontend npm/test/build/audit job
- Docker/Compose/Caddy job
- shell/Repository hygiene job

不能把 Repository hygiene 绿灯当作整条 CI 通过。

如果需要访问 GitHub 或 provider：

- 遵守仓库和主机配置的代理规则。
- 需要代理的请求使用 localhost:7890。
- 不输出 token、credential、private key 或日志中的 secret。

## 14. 已验证测试摘要

最后几轮 agent 报告的验证包括：

- Backend Docker Gradle 8.14.3 + JDK 21 offline test/build：通过。
- PostgreSQL 18.6、Flyway、Hibernate validate：通过。
- Frontend tests：最新历史修复轮报告 9 个 test files、27 个 tests 全部通过。
- Frontend build：通过。
- npm audit：0 vulnerabilities。
- docker compose config：通过。
- backup/restore bash -n：通过。
- git diff --check：通过。
- Yahoo/chart/provider 相关测试使用本地 mock，不依赖真实 provider。
- VOO 实际历史数据已落库 1255 条，adjusted close 完整。

宿主机直接执行 ./gradlew 的已知限制：

- 宿主 Java 为 8。
- wrapper 所需 Gradle 8.14.3 zip 不在对应临时 cache。
- 没有在该轮联网下载。
- 使用本地缓存的 Docker gradle:8.14.3-jdk21、--network none、--offline 完成了等价验证。

Vite 仍有 bundle chunk 大于 500 KB 的 warning，但没有被报告为功能或安全阻断。

## 15. 后续审查建议

新的高级模型 agent 应先做以下事情：

1. 检查 git status --short --branch，确认只剩用户的 AGENTS.md 和本文件是否已经提交。
2. 检查 origin/main 和 GitHub Actions 最新 workflow，不要假设 CI 全绿。
3. 检查 b310f44 中的历史同步和 provider proxy 配置边界。
4. 实际打开局域网预览：

~~~text
http://192.168.2.25:18080/
~~~

5. 用用户自己持有的临时预览凭据登录，不要把密码写入代码、文档或日志。
6. 检查 VOO detail 是否显示 5Y bars、adjusted close、metrics 和 freshness。
7. 检查已有 instrument 在历史同步失败后能否 Retry。
8. 检查 provider failure 是否返回明确状态，而不是静默空数据。
9. 检查全部金融计算单元测试和 Postgres/Flyway 集成测试。
10. 重新查看 CI 失败 job 后再决定是否需要新提交。

不要从身份 catalog 推导价格，不要用当前 holdings 回推历史，不要把市场价当 NAV，不要把 provider API key 放到前端，不要通过删除测试来修 CI。

## 16. v1.1 本地集成状态（2026-08-28）

规划工作包 SA-00 至 SA-09 已在本地 `main` 串行合并。origin 未 push，未打 release tag。用户工作区 `/home/ssy/DCA-DASHBOARD` 仍可能停在 `agent/07-web-quality`，并带有未跟踪的 `AGENTS.md`；不要提交该文件。

本地集成 HEAD 以 `git -C` 隔离 worktree 为准。纳入本次里程碑的工作包：

| 工作包 | 分支 | 作用 |
| --- | --- | --- |
| SA-00 | `agent/00-ci-baseline` | 恢复 Web CI 真实检查 |
| SA-01 | `agent/01-truthful-runtime-mode` | live/demo 边界，禁止故障 fixture 资产 |
| SA-02 | `agent/02-adjusted-close-integrity` | adjusted close 保持 NULL |
| SA-03 | `agent/03-rebuildable-portfolio-history` | snapshot 可失效缓存与历史补算 |
| SA-04 | `agent/04-plan-cycle-invariants` | 执行窗口冻结 cycle intent |
| SA-05 | `agent/05-transaction-hardening` | V013 ledger-order sequence、CSV 边界 |
| SA-07 | `agent/07-web-quality` | live API 模块边界、ESLint、a11y |
| SA-08 | `agent/08-operations-recovery` | dump/restore smoke、session/CSRF、runbook |
| SA-06 | `agent/06-e2e-acceptance` | Playwright + mock Yahoo 独立 volume |
| SA-09 | `agent/09-observability-performance` | dashboard currentViews、低基数 metrics |

Web 验证面：13 个 test files、50 tests，另有 ESLint。API 使用 Java 21；宿主 `JAVA_HOME` 若指向 Java 8 会失败。e2e 使用 `127.0.0.1:38080/38081`，不得占用验收栈 80/443/18080。

GitHub Actions 在未 push 前不能当作全绿证据。发布前仍需在 origin 上看到 CI、e2e、restore smoke 成功；在此之前不要 tag。
