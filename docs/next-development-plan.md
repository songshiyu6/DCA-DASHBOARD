# DCA Terminal 当前状态与后续开发计划

> 代码基线：本文件所在提交（基于 `origin/main@f119cb3`）
>
> 核对日期：2026-08-31
>
> 事实优先级：当前源码与 migration > 当前测试 > 当前运行探测 > 现行文档 > 历史 SA 报告

本文替代此前以 `SA-00` 到 `SA-10` 为主的 v1.1 执行计划。上一阶段已经完成 CI 基线、live/demo 隔离、复权价真实性、snapshot 失效、cycle 冻结、交易/CSV 加固、E2E、运维、安全、前端质量和可观察性工作；对应 `docs/sa-*.md` 保留为特定提交上的历史证据，不能继续当成待办清单。

本文的目标不是重写产品，而是回答三件事：当前代码已经能做什么、当前最真实的缺口是什么、下一阶段按什么顺序投入最合理。

## 1. 产品定位

DCA Terminal 是面向单个长期美国 ETF 投资者的个人执行终端，核心闭环是：

```text
可信行情
   +
真实交易账本
   +
定投计划与周期纪律
   +
投入批次和组合结果
   =
可审计的长期投资工作台
```

产品不下单、不连接券商、不提供 AI 投资建议，不把预测、短线交易或社交功能混进核心路径。后续功能首先服务“记录是否真实、计划是否执行、投入结果是否可解释”。

## 2. 当前功能基线

### 2.1 Dashboard

- 组合市值、成本、净投入、已实现/未实现盈亏、股息、费用和 XIRR。
- Today、YTD 和时间加权年化表现；历史图支持 `1M`、`3M`、`1Y`、`YTD`。
- 当前持仓、价格、份额、均价、权重、盈亏和数据状态。
- 下一次 DCA 金额与 contribution-first 分配建议。
- 年度计划执行进度；实际初始投入月以“初始资金”展示，不重复计入 DCA。
- 页面可见时每分钟刷新当前持仓报价，并保留 provider 降级状态。

### 2.2 Plan

- 当前 UI 只支持一个 active、USD、MONTHLY 计划。
- 可配置月预算、开始日期、执行日窗口、ETF 集合和目标权重。
- 目标权重必须在容差内合计 100%，ETF 不能重复。
- cycle 保存冻结的历史计划意图；后续计划修改只影响仍可修改的未来 cycle。
- cycle 状态包括 `UPCOMING`、`OPEN`、`PARTIAL`、`COMPLETED`、`SKIPPED`。
- contribution-first 推荐只给正缺口资产分配新资金，不通过卖出再平衡。
- 若某月有实际 `INITIAL` BUY 且没有 DCA 执行，该月 DCA cycle 显示为零预算 `SKIPPED`，并拒绝再关联 DCA BUY。

### 2.3 Contributions

- 独立的“投入分析”工作区，当前聚焦 active plan。
- 把实际 BUY 分为 `INITIAL`、cycle-linked `DCA`、`UNPLANNED` 和未归类。
- 显示初始资金、定投资金、累计实际投入、未归类金额和批次数；未归类 BUY 明确标记为账户级队列，不会自动归给 active plan。
- 每个批次计算本金、当前价值、累计 P/L、累计 ROI、成本加权持有天数和数据状态。
- SELL 按全局 FIFO 消耗原始投入批次；split 保持批次成本并调整份额。
- 未归类 BUY 支持批量选择 `INITIAL`/`UNPLANNED`、服务端预览、hash 确认和原子提交；每个确认结果持久审计。
- 只有计划开始日的未关联 BUY 可以归为初始资金；系统不根据日期自动猜测。
- 当前批次 P/L 不含 DIVIDEND 和 standalone FEE，收益率不年化。

### 2.4 ETFs

- 搜索、跟踪、取消跟踪 ETF；本地 canonical catalog 只提供少量已审查的身份兜底。
- ETF detail 展示 quote、NAV（若 provider 真有）、日线/日内图和多周期指标。
- 指标包括 1D、1M、3M、YTD、1Y、3Y CAGR、52 周高低和 drawdown。
- 支持增量 sync、五年 full resync、Retry 和明确的 `FRESH`/`STALE`/`PARTIAL`/`UNAVAILABLE`/`INSUFFICIENT_HISTORY`。
- Yahoo、Twelve Data、Alpha Vantage 通过统一 provider 边界接入；Yahoo 可使用服务端代理。
- provider 失败时保留诚实的旧数据或空缺，不伪造价格、NAV、复权价或搜索结果。

### 2.5 Transactions

- BUY、SELL、DIVIDEND、FEE 的增删改查，支持小数份额和费用。
- 同日交易使用 PostgreSQL sequence 生成稳定 `ledgerOrder`。
- BUY 可以关联 DCA cycle，标记为初始资金或明确标记为 unplanned。
- 交易变更后验证完整 split-aware FIFO ledger；无效超卖整体回滚。
- 历史交易变更会使受影响日期之后的 snapshot 失效并可重建。
- CSV 提供 preview/commit、全批原子性、全局 fingerprint 去重和文件/行/字段上限。
- CSV 当前只通过 `planCycleId` 表达 DCA；未关联 BUY 导入后保持未归类。

### 2.6 Settings、身份与运行

- 中英文界面、系统/浅色/深色主题。
- 可选择 primary/fallback market-data provider，只显示 key 是否已配置，不返回 secret。
- 业务时区固定为 `America/New_York`，设置页不能修改。
- 单用户 session 登录、CSRF、session ID rotation 和登录节流。
- Spring Session 持久化到 PostgreSQL，API 重启通常不使 session 失效。
- Docker Compose 包含 PostgreSQL 18.6、API、Web、Caddy；提供备份、恢复、部署 smoke 和隔离 E2E。
- Flyway 当前链为 `V001` 到 `V017`，Hibernate 生产模式为 `validate`。

## 3. 不可破坏的领域边界

1. Transaction 是用户资产变化的事实源。Holding、lot、cycle execution、snapshot、contribution batch 都是投影。
2. `investment_plan.initial_capital` 是兼容旧库的废弃列，当前实体、API 和 Web 均不映射；实际初始资金只有 `INITIAL` BUY 一个来源。
3. 所有实际初始资金和 DCA 本金必须来自分类后的 BUY 交易。
4. 金额、份额、权重和收益率在后端使用 `BigDecimal`/`NUMERIC`，响应统一为普通十进制字符串；前端使用 `decimal.js-light`。完整 `NUMERIC(20,6/8)` 边界必须持续通过 API raw body 到 normalizer 回归。
5. Market price、adjusted close 和 NAV 是不同事实，缺失时保持缺失。
6. 历史回放不能读取未来交易或未来价格，snapshot 只能是可失效缓存。
7. Provider outage、API outage 和没有搜索结果是三种不同状态。
8. 计划建议只建议和确认，不代表订单，也不能悄悄生成交易。

## 4. 2026-08-31 当前验证证据

### 已验证

- 本轮改造已 rebase 到当时最新 `origin/main@f119cb3`，并同步记录其扩展时段当前估值语义。
- 持久本地栈仍是本轮改造前的部署状态；本轮没有发布、迁移正式数据或执行 full resync。
- Web lint、typecheck、17 个测试文件/77 项测试、production build 全部通过。
- Web production dependencies 的 `npm audit --omit=dev` 为 0 vulnerability。
- API `test build` 在 Java 21、禁用 build cache 且强制重跑时通过。
- API `postgresTest` 在真实 PostgreSQL 18.6/Testcontainers 上通过 `V001`-`V017` Flyway、`V016` 升级回归和 Hibernate validation。
- 隔离 full E2E 的 9 个场景全部通过；除旧核心门禁外，已覆盖 actual initial BUY、账户级未分类队列、raw decimal string、批量 preview/commit 和 audit endpoint。

### 证据边界

- 当前环境没有可用的 `gh` CLI/Actions 授权证据，因此不能声称远端 GitHub Actions 最新 run 已全绿。
- E2E 使用 mock Yahoo 和隔离临时 volume，不替代持久部署的 backup/restore 与 deployment smoke。
- E2E 工具自身的开发依赖 audit 报 2 个 high；这些依赖不进入生产 Web/API 镜像，但仍应单独升级和复测。
- Web build 继续提示 main 与 charts chunk 超过 500 kB。
- 宿主非交互环境当前优先找到损坏的 `/usr/bin/npm`，且 `JAVA_HOME` 指向 Java 8；本轮验证必须显式使用 NVM npm 和 Java 21。CI 容器不受这一宿主 PATH 问题影响，但本地开发说明必须诚实记录前置条件。
- Financial decimal 的全局字符串 serializer 原本已经存在；本轮补的是 API raw body 与 Web normalizer 的 `NUMERIC(20,6/8)` 边界回归，防止合同后续退回 JSON number。

## 5. 当前问题与优先级

R1 的四个 P0 合同缺口已经在本提交完成：planned initial API/DTO/entity
已移除；V017 增加回填、CHECK、partial index 与 audit table；账户级未归类
BUY 已有两阶段批量确认；完整 Decimal 边界已有 API-to-Web 回归。

| ID | 优先级 | 当前事实 | 影响 |
| --- | --- | --- | --- |
| C-05 | P0 | 最新远端 Actions 状态未在本轮得到授权验证。 | 本地通过不能替代远端 release gate。 |
| C-06 | P1 | 批次 P/L 暂不含股息和 standalone fee。 | “这批钱赚了多少”与组合总 P/L 存在可解释但尚未展示的差额。 |
| C-07 | P1 | 计划页展示状态，但没有集中处理即将到期、部分执行和错过月份的行动队列。 | “执行纪律”仍偏观察，下一步操作不够直接。 |
| C-08 | P1 | Live 新建交易/计划含固定 2026 日期和 `Core ETF Plan`/`1500`/`VOO 100%` 样例；CSV textarea 预填两条可提交的样例。 | 默认值漂移、未跟踪资产校验失败，且样例可能被误当作真实账户事实提交。 |
| C-09 | P1 | Provider 只有当前状态，没有可查的健康历史和 market-data gap audit。 | 行情长期缺口可能要到用户打开 ETF 时才发现。 |
| C-10 | P1 | 缺少面向用户的完整导出：交易、贡献分类、cycle intent、快照版本和计算口径。 | 审计、迁移和外部复核成本高。 |
| C-11 | P2 | current valuation 仍可能加载五年日线；transaction UI 取全量列表；history range 主要在前端过滤。 | 数据量增长后会出现不必要的 DB/entity/网络成本。 |
| C-12 | P2 | Actuator 未暴露受保护的 metrics scrape；CSP 仍为 report-only。 | 生产可观察性和浏览器安全收敛尚未完成。 |
| C-13 | P2 | Contributions 页面和 Transactions 资金来源控件仍有中英文/英文硬编码，未完全进入 i18n catalog。 | 文案维护、语言一致性和可访问性容易漂移。 |

## 6. 下一阶段路线图

### R1：事实与接口合同闭环（已完成，等待发布证据）

- R1-01：actual initial capital 只认 `INITIAL` BUY；planned API、DTO 和实体映射已移除，旧列仅兼容数据库。
- R1-02：V017、V016 升级测试、空库 Hibernate validation、非法组合拒绝和 mock-provider browser journey 已建立。
- R1-03：账户级队列、`eligibleForInitial`、批量 preview/confirm、stale hash 防护、行锁、原子提交和持久 audit 已实现。
- R1-04：financial response decimal 固定为字符串；20,6/20,8 边界在 Spring raw JSON 和 Web normalizer 两端锁定。

本地全量 test/build/postgresTest/full E2E 已通过；正式发布前仍需取得远端 Actions、backup/restore 和 deployment smoke 证据。

### R2：执行纪律工作台（P1）

目标是让用户一眼知道“本月还要做什么”，仍然不触碰券商或自动下单。

功能：

- Action queue：即将进入窗口、窗口内未执行、部分执行、已错过、数据不可用。
- 新建交易日期从当前纽约业务日推导，新建计划开始日使用明确规则或空值；计划资产从真实 tracked ETF 或空值开始；CSV textarea 初始值为空，样例只作为不可提交 placeholder/帮助。删除 live 页面中的固定日期和可提交样例事实。
- 月度 close：本月计划、实际 BUY、差额、分类完整性和行情 freshness 的确认页。
- 从 recommendation 预填一个手工 BUY 表单；用户确认后才创建 transaction。
- missed/partial cycle 的解释和处理选择：保持历史、补记实际交易、明确跳过；不允许静默改历史 intent。
- 可选的本地/邮件提醒只发送计划状态，不包含 secret 或完整持仓；提醒系统与交易写入解耦。

验收：所有行动都能追溯到 plan/cycle/transaction；关闭提醒不会改变业务状态；没有任何 broker/order API。

### R3：投入与绩效解释（P1）

按以下顺序扩展，不先堆更多指标：

1. 在 UI 中解释 contribution ROI、portfolio TWR、XIRR 的差异。
2. 增加 initial vs DCA 的 P/L bridge：本金、已实现、未实现、被排除的股息/费用。
3. 决定股息和 standalone fee 的归属规则。推荐先显示“未归属差额”，不要按持仓比例猜测历史来源。
4. 增加按月批次趋势、累计投入与当前价值对比、批次明细展开。
5. 给每个结果显示 `asOf`、price source、freshness 和 calculation version/provenance。

验收：Contribution bucket 合计与 portfolio 总计的差额可逐项解释；缺价格时不显示零收益；同一计算在 API、UI 和导出中口径一致。

### R4：行情可靠性与运维（P1）

- Market-data gap audit：按 tracked instrument 显示首末日期、预期交易日缺口、adjusted-close 缺口、最后成功 sync。
- Provider health history：operation、provider、outcome、延迟、rate-limit/error 分类，保持低基数且不记录 symbol/secret。
- 有界 repair queue：单 ETF/full history/失败重试可观察、可取消、不会先清空旧数据。
- 正式配置一个有 SLA/配额的 secondary provider；Yahoo 继续作为非正式来源而不是唯一可靠性承诺。
- 提供受保护的运维视图或 management-only metrics；不要直接公开 Actuator。
- 在真实浏览器 CSP audit 后，将 report-only 分阶段收紧为 enforcement。

验收：provider 全部失败时账户页面仍可打开并明确 stale/unavailable；repair 可从中断点重试；监控不泄露 ticker、notes、cookie、SQL 或 key。

### R5：导出、审计与恢复（P1）

- 导出 transaction ledger，包含 ledger order、cycle、contribution type/plan 和 import fingerprint 的安全审计表示。
- 导出 plan/cycle frozen intent、实际执行、contribution batches 和计算口径版本。
- 提供不含 secret/session 的账户数据包 manifest 和校验和。
- Restore smoke 增加 `V017` contribution attribution/audit、Spring Session 处理选择和关键投影一致性断言。
- 导入前必须 preview；不支持直接导入 editable holdings/snapshots。

验收：导出的事实可在空数据库中恢复并重建相同 holding/cycle/contribution 结果；snapshot 不作为必需事实。

### R6：容量、前端质量与维护（P2）

- Current valuation 改为优先读取 quote/目标日期附近的必要日线，不为当前价加载整段五年历史。
- Transaction API 增加向后兼容分页和 server-side filters；前端表格再做虚拟化。
- Dashboard history 接受真实 range，减少始终返回 `ALL` 后在浏览器过滤。
- 在 PostgreSQL 18.6 上重做 20/100/1,000 instruments 与 10k/100k transactions 容量基线，再决定 index/cache。
- Contribution 相关页面与表单文案全部进入 i18n catalog，补键盘、screen-reader 和移动端 smoke。
- 分析 bundle，继续按路由/图表拆包；升级 E2E Playwright 依赖并清除开发依赖 high audit。
- 迁移已弃用的 Spring test mock annotations，降低下一次 Spring Boot 升级风险。

性能改动必须先证明查询形状或用户延迟问题；不能用跨请求缓存掩盖错误投影，也不能牺牲财务一致性。

## 7. 推荐执行顺序

```text
R1 fact/interface contract complete
        |
        +------------------+
        v                  v
R2 execution discipline   R4 market reliability
        |                  |
        +--------+---------+
                 v
R3 analytics explanation + R5 export/recovery
                 |
                 v
R6 capacity and maintenance
```

R1 已完成。下一步优先清除 live 表单中的可提交样例事实并建设执行行动队列，不先扩展更多 contribution 图表。

## 8. Release gates

### 每个功能 PR

- 先有能证明旧行为/缺口的失败回归，再改实现。
- 明确 API/schema/config/financial semantics 是否变化。
- Web：lint、typecheck、test、build。
- API：test/build；涉及 JPA/Flyway 时强制执行非缓存 `postgresTest`。
- `git diff --check`，只修改约定文件，保留用户工作树。
- Provider 测试只用 mock/fixture；需要联网时遵守 `AGENTS.md` 的代理规则。

### R1 release candidate

- Contribution 的 PostgreSQL、API、Web 和 E2E 场景全部通过。
- Initial-capital target/actual 命名和兼容策略已定，不存在模糊双事实源。
- Financial decimal 在 API raw body 到浏览器计算之间通过 schema 边界值无损往返测试。
- 未归类 BUY 有可审计处理路径。
- 远端 GitHub Actions 当前 commit 全绿，并保存 job 级证据。
- Full E2E、backup/restore smoke、deployment smoke 和当前文档全部通过。
- 不打 tag、不 push、不发布，除非用户明确授权。

### 长期运行版本

- 生产/本地正式栈完成升级前备份和恢复校验。
- 当前 Flyway version、核心行数与 portfolio/contribution control totals 已核对。
- 登录、CSRF、logout、session restart、provider outage 和数据缺口路径均验证。
- Caddy、cookie、CSP 和 provider key 暴露面经过审计。

## 9. 产品成功指标

这些指标用于判断功能是否解决问题，不作为金融收益目标：

- 活动计划中的 BUY 分类完整率。
- 每月 cycle 在窗口结束前的明确完成/跳过率。
- 未归类金额和未解释 P/L 差额随时间下降。
- Tracked ETF 的历史/adjusted-close 完整率与最后成功 sync 年龄。
- Provider failure 到用户可见状态、重试和恢复的时间。
- Backup restore 后 control totals 与投影一致率。
- Dashboard、Transactions、Contributions 在目标数据量下的 p95 响应时间。
- Release candidate 的本地与远端门禁重复通过率。

## 10. 明确不进入近期规划

- 券商连接、自动下单、真实订单状态。
- 期权、加密货币、个股研究、技术指标和实时 Level 2。
- AI 选股、价格预测、新闻和社交功能。
- 多用户 SaaS、团队权限和公开分享。
- 税务申报、多币种会计和未经定义的回测系统。

只有在单用户 ETF DCA 的真实性、执行纪律、可解释性和恢复能力已经稳定后，才重新讨论这些边界。

## 11. 立即下一步

1. 删除 live 新建交易、计划和 CSV 弹窗中的固定日期与可提交样例，按 R2 使用空值或当前纽约业务日。
2. 建设 action queue，先覆盖窗口内未执行、部分执行、错过和行情不可用。
3. 补 contribution audit 的用户可读历史视图和导出合同，但不允许从 audit 修改账本。
4. 升级 Playwright 开发依赖并重新跑 full E2E，清除当前 dev audit high。
5. 取得当前提交的远端 Actions、backup/restore 和 deployment smoke 证据后再发布。
