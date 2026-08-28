# SA-10 release evidence

```text
工作包：SA-10
分支：agent/10-release-integration
提交：575902595947e3bc66d4675a994a1b74e3d65468

已验证的旧行为：
- 文档仍写 migration 到 V012、前端 9 files/27 tests、CI 仅 compose config
- agent-handoff 仍指向已 push 的 v1 preview HEAD，未记录 SA-00–SA-09 本地集成
- Docker 构建会把本地 apps/api/build 打进上下文，导致 e2e API unhealthy

变更：
- 更新 handoff/api/architecture/market-data/calculations/README/runbook
- 记录 V013 前进式 schema 与不可回滚边界
- 增加根 .dockerignore，排除 build/node_modules
- 在最新本地 main 重跑命令矩阵和第 9 节场景映射

新增/修改测试：无产品测试；文档与构建上下文隔离。

执行命令（/tmp/dca-dashboard-sa10，Java 21）：
- git diff --check -> PASS
- docker compose config --quiet + bash -n 五个脚本 -> PASS
- apps/web npm ci / audit --omit=dev / lint / typecheck / test --run / build -> PASS（13 files, 50 tests, 0 prod vulns）
- apps/api ./gradlew test --rerun-tasks --no-daemon -> PASS（SA-09 合并后 main）
- apps/api ./gradlew postgresTest --rerun-tasks --no-daemon -> PASS
- bash deploy/scripts/backup-restore-smoke.sh -> PASS
- DCA_E2E_SUITE=full bash e2e/run.sh -> PASS（8 passed）

未解决风险：
- 未 push，GitHub Actions 不能当作全绿证据，因此不打 tag
- e2e Playwright 开发依赖 npm audit 报 2 high；不进入生产镜像
- 容量基线来自 H2，不是 PostgreSQL 18.6
- 验收栈 18080 未在本轮重新做浏览器 CSP enforcement 检查；Caddy 仍为 report-only
- current ledger 仍加载五年日线

是否改变 API/schema/config：no API/schema。新增 .dockerignore（构建上下文）。文档记录 V013 回滚边界。
```

## Section 9 scenario mapping

| Scenario | Evidence |
| --- | --- |
| A Live API 不可达 | `e2e/tests/02-api-down.spec.ts` |
| B Provider 不可达 | `e2e/tests/03-provider-failure.spec.ts` |
| C Adjusted close 缺失 | `e2e/tests/04-api-integration.spec.ts` missing-adj; MarketDataServiceCorrectnessTest |
| D Backdated mutation | `e2e/tests/04-api-integration.spec.ts` backdated BUY; SA-03 portfolio tests |
| E Partial snapshot | SA-03 history coverage tests in API suite |
| F Plan cycle freeze | e2e frozen planned amount; PlanServiceCorrectnessTest |
| G CSV all-or-nothing | e2e CSV duplicate commit; SA-05 transaction tests |
| H Backup/restore | `deploy/scripts/backup-restore-smoke.sh` this run |
| I Auth/CSRF | e2e CSRF test; AuthControllerSessionTest; deployment smoke in CI |
