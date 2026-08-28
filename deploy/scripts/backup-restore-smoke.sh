#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$DEPLOY_DIR/.." && pwd)"
COMPOSE_FILE="${DCA_SMOKE_COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.yml}"
if [[ "$COMPOSE_FILE" != /* ]]; then
    COMPOSE_FILE="$REPO_ROOT/$COMPOSE_FILE"
fi

if ! command -v docker >/dev/null 2>&1; then
    printf 'docker is required.\n' >&2
    exit 2
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
    printf 'Compose file not found: %s\n' "$COMPOSE_FILE" >&2
    exit 2
fi

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
SOURCE_PROJECT="dca-sa08-src-$RUN_ID"
TARGET_PROJECT="dca-sa08-dst-$RUN_ID"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dca-terminal-restore-smoke.XXXXXX")"
SOURCE_ENV="$TEMP_DIR/source.env"
TARGET_ENV="$TEMP_DIR/target.env"
BACKUP_DIR="$TEMP_DIR/backups"
SOURCE_DB="dca_restore_source"
TARGET_DB="dca_restore_target"
SOURCE_USER="dca_restore"
TARGET_USER="dca_restore"
SOURCE_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
TARGET_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

write_env() {
    local file="$1"
    local project="$2"
    local database="$3"
    local user="$4"
    local password="$5"

    printf '%s\n' \
        "COMPOSE_PROJECT_NAME=$project" \
        "COMPOSE_FILE=$COMPOSE_FILE" \
        'POSTGRES_IMAGE=postgres:18.6-alpine' \
        "POSTGRES_DB=$database" \
        "POSTGRES_USER=$user" \
        "POSTGRES_PASSWORD=$password" \
        'APP_DOMAIN=localhost' \
        'CADDY_EMAIL=smoke@example.invalid' \
        'CADDY_IMAGE=caddy:2-alpine' \
        'APP_PASSWORD_HASH=unused-in-restore-smoke' \
        'APP_SECURITY_ENABLED=false' \
        'APP_COOKIE_SECURE=false' \
        'SPRING_PROFILES_ACTIVE=prod' \
        'FLYWAY_ENABLED=true' \
        'MARKET_SYNC_ENABLED=false' \
        'VITE_API_BASE_URL=/api/v1' \
        'VITE_APP_MODE=live' \
        'MARKET_DATA_PRIMARY_PROVIDER=YAHOO' \
        'MARKET_DATA_FALLBACK_PROVIDER=TWELVE_DATA' \
        'YAHOO_PROXY_URL=' \
        'TWELVE_DATA_API_KEY=' \
        'ALPHA_VANTAGE_API_KEY=' \
        "BACKUP_DIR=$BACKUP_DIR" >"$file"
}

wait_for_postgres() {
    local env_file="$1"
    local user="$2"
    local database="$3"
    local -a compose=(docker compose --env-file "$env_file" -f "$COMPOSE_FILE")

    for ((attempt = 1; attempt <= 60; attempt++)); do
        if "${compose[@]}" exec -T postgres pg_isready -U "$user" -d "$database" >/dev/null 2>&1; then
            return
        fi
        sleep 2
    done
    printf 'PostgreSQL did not become ready for the temporary project.\n' >&2
    exit 1
}

wait_for_api() {
    local env_file="$1"
    local -a compose=(docker compose --env-file "$env_file" -f "$COMPOSE_FILE")

    for ((attempt = 1; attempt <= 60; attempt++)); do
        if "${compose[@]}" exec -T api wget -q -O - http://127.0.0.1:8080/actuator/health \
                | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
            return
        fi
        sleep 2
    done
    printf 'API did not become healthy in the temporary project.\n' >&2
    "${compose[@]}" logs --no-color --tail=200 api >&2 || true
    exit 1
}

run_sql() {
    local env_file="$1"
    local user="$2"
    local database="$3"
    shift 3
    local -a compose=(docker compose --env-file "$env_file" -f "$COMPOSE_FILE")
    "${compose[@]}" exec -T postgres psql --set=ON_ERROR_STOP=1 \
        --username="$user" --dbname="$database" "$@"
}

collect_assertions() {
    local env_file="$1"
    local user="$2"
    local database="$3"
    local output="$4"

    run_sql "$env_file" "$user" "$database" --tuples-only --no-align <<'SQL' >"$output"
SELECT 'flyway=' || version
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 1;
SELECT 'instrument_count=' || count(*) FROM instrument;
SELECT 'price_count=' || count(*) FROM market_price_daily;
SELECT 'plan_asset=' || target_weight::text
FROM investment_plan_asset
WHERE id = '00000000-0000-0000-0000-000000000003';
SELECT 'cycle_asset=' || target_weight::text || ':' || planned_amount::text
FROM investment_plan_cycle_asset
WHERE id = '00000000-0000-0000-0000-000000000007';
SELECT 'transaction_count=' || count(*) FROM investment_transaction;
SELECT 'transaction_cycle=' || (plan_cycle_id = '00000000-0000-0000-0000-000000000004')::text
FROM investment_transaction
WHERE id = '00000000-0000-0000-0000-000000000005';
SELECT 'portfolio=' || market_value::text || ',' || cost_basis::text || ','
       || net_cash_flow::text || ',' || unrealized_pl::text
FROM portfolio_snapshot_daily
WHERE snapshot_date = DATE '2026-08-28';
SQL
}

cleanup() {
    set +e
    if [[ -f "$SOURCE_ENV" ]]; then
        docker compose --env-file "$SOURCE_ENV" -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1
    fi
    if [[ -f "$TARGET_ENV" ]]; then
        docker compose --env-file "$TARGET_ENV" -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1
    fi
    rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

umask 077
mkdir -p "$BACKUP_DIR"
write_env "$SOURCE_ENV" "$SOURCE_PROJECT" "$SOURCE_DB" "$SOURCE_USER" "$SOURCE_PASSWORD"
write_env "$TARGET_ENV" "$TARGET_PROJECT" "$TARGET_DB" "$TARGET_USER" "$TARGET_PASSWORD"

SOURCE_COMPOSE=(docker compose --env-file "$SOURCE_ENV" -f "$COMPOSE_FILE")
TARGET_COMPOSE=(docker compose --env-file "$TARGET_ENV" -f "$COMPOSE_FILE")

"${SOURCE_COMPOSE[@]}" config --quiet
"${SOURCE_COMPOSE[@]}" up -d --build api
wait_for_postgres "$SOURCE_ENV" "$SOURCE_USER" "$SOURCE_DB"
wait_for_api "$SOURCE_ENV"

run_sql "$SOURCE_ENV" "$SOURCE_USER" "$SOURCE_DB" <<'SQL'
INSERT INTO instrument (
    id, symbol, name, exchange, currency, instrument_type, issuer,
    expense_ratio, aum, dividend_yield, data_provider, tracked, data_status,
    created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000001', 'SMK', 'SA-08 Smoke ETF', 'NYSE', 'USD',
    'ETF', 'DCA Terminal', 0.001, 1000000, 0.01, 'YAHOO', TRUE, 'FRESH',
    TIMESTAMPTZ '2026-08-28 00:00:00+00', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO market_price_daily (
    id, instrument_id, trade_date, open, high, low, close, adjusted_close,
    volume, source, created_at
) VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001', DATE '2026-08-28',
    10, 12, 9, 11, 11, 1000, 'YAHOO', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO investment_plan (
    id, name, currency, frequency, monthly_budget, start_date,
    execution_start_day, execution_end_day, status, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000006', 'SA-08 Smoke Plan', 'USD', 'MONTHLY',
    500, DATE '2026-08-01', 1, 7, 'ACTIVE',
    TIMESTAMPTZ '2026-08-28 00:00:00+00', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO investment_plan_asset (
    id, plan_id, instrument_id, target_weight, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000006',
    '00000000-0000-0000-0000-000000000001', 1,
    TIMESTAMPTZ '2026-08-28 00:00:00+00', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO investment_plan_cycle (
    id, plan_id, period, planned_amount, executed_amount, status,
    opened_at, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000004',
    '00000000-0000-0000-0000-000000000006', '2026-08', 500, 100, 'PARTIAL',
    TIMESTAMPTZ '2026-08-01 00:00:00+00',
    TIMESTAMPTZ '2026-08-28 00:00:00+00', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO investment_plan_cycle_asset (
    id, cycle_id, instrument_id, target_weight, planned_amount, executed_amount
) VALUES (
    '00000000-0000-0000-0000-000000000007',
    '00000000-0000-0000-0000-000000000004',
    '00000000-0000-0000-0000-000000000001', 1, 500, 100
);

INSERT INTO investment_transaction (
    id, instrument_id, plan_cycle_id, transaction_type, trade_date,
    quantity, unit_price, amount, fee, currency, notes, ledger_order,
    created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000005',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000004', 'BUY', DATE '2026-08-28',
    10, 100, NULL, 0, 'USD', 'SA-08 temporary restore fixture', 1,
    TIMESTAMPTZ '2026-08-28 00:00:00+00', TIMESTAMPTZ '2026-08-28 00:00:00+00'
);

INSERT INTO portfolio_snapshot_daily (
    id, snapshot_date, market_value, cost_basis, net_cash_flow, realized_pl,
    unrealized_pl, dividend_income, total_fees, data_status, created_at
) VALUES (
    '00000000-0000-0000-0000-000000000008', DATE '2026-08-28',
    1100, 1000, 1000, 0, 100, 0, 0, 'FRESH',
    TIMESTAMPTZ '2026-08-28 00:00:00+00'
);
SQL

collect_assertions "$SOURCE_ENV" "$SOURCE_USER" "$SOURCE_DB" "$TEMP_DIR/source.assertions"
DCA_ENV_FILE="$SOURCE_ENV" "$SCRIPT_DIR/backup-postgres.sh" >"$TEMP_DIR/backup.log"
BACKUP_FILE="$(find "$BACKUP_DIR/daily" -maxdepth 1 -type f -name '*.sql.gz' -print -quit)"
if [[ -z "$BACKUP_FILE" ]]; then
    printf 'Backup script did not create a dump.\n' >&2
    exit 1
fi
gzip -t "$BACKUP_FILE"

"${TARGET_COMPOSE[@]}" config --quiet
"${TARGET_COMPOSE[@]}" up -d postgres
wait_for_postgres "$TARGET_ENV" "$TARGET_USER" "$TARGET_DB"
DCA_ENV_FILE="$TARGET_ENV" DCA_RESTORE_SKIP_SAFETY_BACKUP=1 \
    "$SCRIPT_DIR/restore-postgres.sh" --confirm "$BACKUP_FILE" >"$TEMP_DIR/restore.log"
wait_for_postgres "$TARGET_ENV" "$TARGET_USER" "$TARGET_DB"
"${TARGET_COMPOSE[@]}" up -d --build api
wait_for_api "$TARGET_ENV"
collect_assertions "$TARGET_ENV" "$TARGET_USER" "$TARGET_DB" "$TEMP_DIR/target.assertions"

if ! cmp -s "$TEMP_DIR/source.assertions" "$TEMP_DIR/target.assertions"; then
    printf 'Backup/restore assertions differ between the source and restored databases.\n' >&2
    diff -u "$TEMP_DIR/source.assertions" "$TEMP_DIR/target.assertions" >&2 || true
    exit 1
fi

printf 'PostgreSQL 18.6 backup/restore smoke passed.\n'
