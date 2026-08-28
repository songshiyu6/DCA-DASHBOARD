# DCA Terminal 运维运行手册

本手册覆盖当前 `deploy/docker-compose.yml` 的单机部署。所有命令都在仓库根目录执行。部署凭据、数据库密码、provider key 和 session cookie 只通过受保护的环境或 secret manager 传递，不写入 Git、浏览器 bundle 或命令输出。

## 0. 安全边界

- 生产使用 `APP_SECURITY_ENABLED=true`、`APP_COOKIE_SECURE=true`、HTTPS 和 `APP_PASSWORD_HASH`。`APP_PASSWORD_HASH` 使用批准的 secret manager 注入；不要把明文密码或 hash 提交到仓库。
- `APP_LOGIN_MAX_ATTEMPTS` 和 `APP_LOGIN_THROTTLE_WINDOW_SECONDS` 控制同一用户名与来源地址的登录失败窗口。成功登录会清除该窗口，过期窗口会被淘汰。当前 `deploy/docker-compose.yml` 未把这两个变量传入 API 容器时，容器使用 `application.yml` 的默认值 5/900。
- smoke 只能使用临时数据库、临时 Docker volume 和临时测试凭据。不得把 `DCA_ENV_FILE` 指向生产 `.env`，不得把 smoke 指向生产 volume。
- 执行 curl 时不要使用 `--verbose`，不要打印 response headers/body、cookie、CSRF token 或密码。脚本把这些内容写入权限为 `0700` 的临时目录并在退出时删除。
- 当前 compose 的 `postgres_data` 使用 PostgreSQL 18+ 的版本化数据目录。PostgreSQL major 变更必须走 dump/restore，不能把旧 major 的数据目录直接挂到新 major。

## 1. 首次部署

1. 从已审查的版本检出仓库，确认工作树干净，并复制环境模板。仅首次部署执行第二条命令；已有 `.env` 时不要覆盖它。

   ```bash
   cd /opt/dca-terminal
   git status --short --branch
   test ! -e deploy/.env
   umask 077
   install -m 600 deploy/.env.example deploy/.env
   ```

2. 编辑 `deploy/.env`，至少替换 `APP_DOMAIN`、`CADDY_EMAIL`、`POSTGRES_PASSWORD`、`APP_USERNAME` 和 `APP_PASSWORD_HASH`，并确认 `APP_SECURITY_ENABLED=true`、`APP_COOKIE_SECURE=true`、`FLYWAY_ENABLED=true`。生产不要把 market provider key 放到 web 构建参数。

3. 在启动前只做配置校验，不把解析后的配置输出到日志：

   ```bash
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
   ```

4. 启动并确认四个服务健康。首次构建按仓库代理策略配置 `HTTP_PROXY`/`HTTPS_PROXY`；需要代理时使用 `localhost:7890`，不要绕过既定代理。

   ```bash
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
   ```

5. 用一次性临时密码运行部署 smoke。密码通过交互输入，不写入脚本、文档或 shell 命令行；不要在 smoke 失败时打印临时目录内容。

   ```bash
   read -r -s DCA_SMOKE_PASSWORD
   printf '\n' >&2
   export DCA_SMOKE_PASSWORD
   DCA_SMOKE_BASE_URL="https://${APP_DOMAIN}" \
   DCA_SMOKE_USERNAME="${APP_USERNAME}" \
   DCA_SMOKE_EXPECT_SECURE_COOKIES=1 \
   bash deploy/scripts/smoke-deployment.sh
   unset DCA_SMOKE_PASSWORD
   ```

   成功标准是 Caddy `/`、API health、未登录 session、CSRF bootstrap、login、settings、dashboard 和 logout 全部通过，且 logout 后旧 session/CSRF mutation 被拒绝。局域网 HTTP 预览只能显式设置 `DCA_SMOKE_EXPECT_SECURE_COOKIES=0` 并使用与之匹配的 `APP_COOKIE_SECURE=false`；不能把这种配置用于公网。

6. 安装每日备份 timer（路径需与实际部署目录一致）：

   ```bash
   sudo install -m 0644 deploy/systemd/dca-terminal-backup.service /etc/systemd/system/
   sudo install -m 0644 deploy/systemd/dca-terminal-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now dca-terminal-backup.timer
   systemctl list-timers dca-terminal-backup.timer
   ```

## 2. 日常升级

1. 选择已经审查并可回滚的 commit，确认 `deploy/.env` 保留在主机且权限为 `600`。不要执行 `down -v`。

   ```bash
   git status --short --branch
   git rev-parse --verify HEAD
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
   ```

2. 构建并启动应用，升级后重新运行第 1 节的 smoke：

   ```bash
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build api web caddy
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
   ```

3. Flyway migration 是前进式的。升级前保存经过验证的备份；不要编辑或删除已经应用的 migration，也不要把旧应用直接用于不兼容的新 schema。

## 3. Backup verification

执行备份脚本会先检查 Compose/PostgreSQL，就地生成 gzip plain SQL，并执行 `gzip -t`：

```bash
DCA_ENV_FILE=deploy/.env deploy/scripts/backup-postgres.sh
latest_backup="$(find deploy/backups/daily -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
test -n "$latest_backup"
gzip -t -- "$latest_backup"
stat -c '%a %n' -- "$latest_backup"
```

确认输出权限至少不允许其他用户读取，并把备份复制到与主机不同的受保护位置。只看到 `Backup created` 和路径即可；不要解压到公共目录或把 SQL 内容放进日志。

## 4. PostgreSQL major upgrade

下面以从当前 major 升级到新 major 为例。`NEW_PROJECT` 必须是新的 Compose project，这会创建新的 `postgres_data` volume；旧 project/volume 保留到恢复验收完成。不要让新 major 直接打开旧 volume。

1. 在停止写入前创建并验证旧数据库备份：

   ```bash
   cd /opt/dca-terminal
   DCA_ENV_FILE=deploy/.env deploy/scripts/backup-postgres.sh
   latest_backup="$(find deploy/backups/daily -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
   gzip -t -- "$latest_backup"
   ```

2. 停止旧 project，但不要删除 volume。复制 `.env` 到权限受限的临时文件，改用新 project 和目标 PostgreSQL image；`NEW_POSTGRES_IMAGE` 应是已批准的目标 major。

   ```bash
   old_compose=(docker compose --env-file deploy/.env -f deploy/docker-compose.yml)
   upgrade_env="$(mktemp)"
   umask 077
   cp -- deploy/.env "$upgrade_env"
   chmod 600 "$upgrade_env"
   NEW_PROJECT=dca-terminal-pg-major-upgrade
   NEW_POSTGRES_IMAGE=postgres:19.0-alpine
   sed -i \
     -e "s/^COMPOSE_PROJECT_NAME=.*/COMPOSE_PROJECT_NAME=${NEW_PROJECT}/" \
     -e "s#^POSTGRES_IMAGE=.*#POSTGRES_IMAGE=${NEW_POSTGRES_IMAGE}#" \
     "$upgrade_env"
   "${old_compose[@]}" stop caddy web api
   "${old_compose[@]}" down --remove-orphans
   ```

3. 启动新 PostgreSQL。它使用新 project 的新 volume；先等 healthcheck，再恢复备份。新空 volume 不需要 safety backup，所以这里只在已确认目标为空时使用 `DCA_RESTORE_SKIP_SAFETY_BACKUP=1`。

   ```bash
   new_compose=(docker compose --env-file "$upgrade_env" -f deploy/docker-compose.yml)
   "${new_compose[@]}" up -d postgres
   "${new_compose[@]}" ps
   DCA_ENV_FILE="$upgrade_env" DCA_RESTORE_SKIP_SAFETY_BACKUP=1 \
     deploy/scripts/restore-postgres.sh --confirm "$latest_backup"
   "${new_compose[@]}" up -d --build
   ```

4. 用第 1 节 smoke 和最小数据库核对完成验收后，再把经过验证的 project/image 配置提升为新的部署配置，并重新安装 systemd service 中的 `DCA_ENV_FILE`。升级失败时，停止新 project、保留新 volume 供调查，用原 `deploy/.env` 启动旧 project；旧 volume 从未被覆盖。

5. 验收完成后才删除临时 env 和旧资源。删除前确认备份已在异机保存；不要使用没有明确 project 名称的递归删除命令。

## 5. Restore drill

Restore 是破坏性操作。先进入维护窗口，停止会写数据库的 API，保留 PostgreSQL 容器运行，然后创建 safety backup。默认 restore 脚本会创建 safety backup；只有在第 4 节的新空 volume 流程中才跳过它。

```bash
compose=(docker compose --env-file deploy/.env -f deploy/docker-compose.yml)
"${compose[@]}" stop caddy web api
gzip -t -- "$BACKUP_FILE"
DCA_ENV_FILE=deploy/.env deploy/scripts/restore-postgres.sh --confirm "$BACKUP_FILE"
"${compose[@]}" up -d api web caddy
"${compose[@]}" ps
```

恢复后依次确认 API health、最新 Flyway version、关键关联数据和 portfolio 总计，再运行部署 smoke。验证未完成前不要删除旧 volume、safety backup 或原始 dump。需要可重复的全流程验证时，在没有生产 `.env`、volume 或 provider key 的环境运行：

```bash
bash deploy/scripts/backup-restore-smoke.sh
```

该脚本会在两个独立临时 Compose project 中真实执行 PostgreSQL 18.6 dump、gzip 校验、新 volume restore、Flyway 启动校验和关联数据断言，不调用 market provider。

## 6. Full market-history resync

只有在 provider 配置、代理和配额确认可用时执行 full resync。provider key 只在 API 容器环境中，不能放进 web 环境或浏览器请求。不要用 fixture、当前持仓、raw close 或零值补齐历史。

正常操作优先使用 ETF detail 页的 Retry/同步动作。需要审计 API 响应时，在已认证的临时 shell 中使用已有 session/CSRF 变量；不要把它们写入命令历史或输出：

```bash
curl --silent --show-error --fail \
  --cookie "$COOKIE_JAR" \
  --header "$CSRF_HEADER: $CSRF_TOKEN" \
  --request POST "https://${APP_DOMAIN}/api/v1/instruments/${SYMBOL}/sync/full" \
  --output "$RESYNC_RESPONSE"
```

检查返回的 status/source/asOf/retrievedAt 和数据库行数；provider 不可达时应保留明确的 unavailable/stale 状态并重试，不得把失败伪装成新鲜数据。

## 7. Application image rollback

回滚只针对应用版本，不删除数据库 volume，不回滚已应用 schema。当前 compose 从检出的 revision 构建应用，因此回滚到已验证的旧 revision 后执行：

```bash
git status --short --branch
git rev-parse --verify KNOWN_GOOD_COMMIT
# 在维护窗口检出已验证 revision 后：
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build api web caddy
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

如果旧应用不能读取当前 schema，立即停止继续回滚，恢复兼容的应用版本或按 Restore drill 将数据库恢复到匹配快照。任何 rollback 都必须重新执行 health、session/CSRF/login/logout smoke；不要用 `docker compose down -v`。

## 8. 何时不能回滚 schema

- 不编辑、删除或重排已经发布的 Flyway migration，不执行“向下 migration”来配合旧应用。
- 新 migration 已改变列含义、约束、索引、关联关系或删除数据时，schema 不能靠旧镜像回滚。
- 优先发布向前兼容的修复 migration；若数据已损坏或必须回到历史状态，使用已验证 dump/restore 到隔离 volume，再以匹配的应用版本验收。
- 任何恢复或迁移都要记录 Flyway version、transaction/plan/price/snapshot 关联行和 portfolio 关键总计，避免只凭页面是否打开判断成功。

## 9. 浏览器兼容与 headers 审计

在添加 Caddy headers 前已核对当前 web 构建：Google Fonts 使用 `fonts.googleapis.com`/`fonts.gstatic.com`，React 使用 inline style，ECharts 使用 canvas/HTML tooltip，Lightweight Charts 使用 canvas，API 请求为同源 `/api`。因此 Caddy 当前只发送 `Content-Security-Policy-Report-Only`，允许这些已审计资源和 `connect-src 'self'`；不会以 CSP enforcement 阻断 Vite bundle、图表、样式或 API。HTTPS 才发送 HSTS；HTTP 局域网预览不会收到 HSTS。

浏览器 smoke 时打开 DevTools Console/Issues，确认 report-only 没有未预期的资源违规，再按需要收紧策略。不得把 report-only 改成 enforcement，除非重新完成资源审计和真实浏览器验证。
