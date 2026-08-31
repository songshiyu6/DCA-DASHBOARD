# SA-06 E2E acceptance report

> Historical evidence for the SA-06 commit only. For the current code and
> roadmap, use [`agent-handoff.md`](./agent-handoff.md) and
> [`next-development-plan.md`](./next-development-plan.md).

```text
工作包：SA-06
分支：agent/06-e2e-acceptance
提交：this commit on agent/06-e2e-acceptance

已验证的旧行为：
- live web still talks to /api/v1; demo fixtures are not used when VITE_APP_MODE=live
- session login still uses CSRF cookie + header
- Caddy still proxies /api/* to the API and everything else to web
- Yahoo chart/search/quoteSummary can be pointed at YAHOO_BASE_URL
- incremental instrument sync is a no-op when today's bar is already stored
- quotes stay Caffeine-cached for 1 minute; settings updates evict that cache
- production compose ports 80/443/18080 are untouched; e2e uses 127.0.0.1:38080/38081

变更：
- Isolated e2e compose override with PostgreSQL 18.6, API, web, Caddy on :80,
  and a mock Yahoo server published only on the edge network
- Mock search/chart/quoteSummary plus fail/empty/missing-adj modes
- Playwright 1.55 Chromium journey covering E2E-01/02/03 and API contracts
- CI e2e job: PR default smoke, workflow_dispatch smoke|full
- e2e-only MARKET_QUOTE_TTL_SECONDS=0 so a failed quote fetch is observable
  without waiting for the production 60s quote TTL

新增/修改测试：
- E2E-01 login/track VOO/plan/BUY -> ledger-derived $1,100.00 / $1,000.00 / 10 shares persist after refresh
- E2E-02 API 503 (except /api/v1/auth/) -> error/retry, no fixture markers
- E2E-03 mock 429 -> stored VOO $110.00 becomes STALE/"Data delayed"; QQQ with no history is UNAVAILABLE and has no chart
- CSRF mutation rejected without token; accepted with token
- CSV duplicate preview commit is all-or-nothing
- backdated BUY does not look ahead in portfolio history
- missing adjusted close leaves 1Y metric null with PARTIAL/INSUFFICIENT_HISTORY/UNAVAILABLE
- current plan cycle planned amount stays frozen after a later budget edit

执行命令：
- DCA_E2E_SUITE=smoke bash e2e/run.sh -> PASS (1 passed)
- DCA_E2E_SUITE=full bash e2e/run.sh -> PASS (8 passed)

未解决风险：
- e2e Caddyfile uses site :80 because Host 127.0.0.1 does not match http://localhost
- mock-yahoo must be on edge only so 127.0.0.1:38081 publishes; API must stay on backend+edge for postgres DNS
- incremental /sync cannot prove provider failure when the last bar is already today; E2E-03 uses /sync/full plus a settings write to evict the quote cache
- Playwright Chromium install uses HTTP(S)_PROXY=http://127.0.0.1:7890 locally, then unsets proxy before tests so localhost is not proxied
- CI e2e job was not executed on GitHub in this work package

是否改变 API/schema/config：yes
若 yes：e2e-only compose/Caddy/Yahoo base URL and quote TTL. No Flyway migration, no production Caddyfile/compose change, no API envelope change. Roll back by removing the e2e override and CI job.
```
