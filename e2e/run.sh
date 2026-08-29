#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deploy/docker-compose.yml"
OVERRIDE_FILE="$REPO_ROOT/deploy/docker-compose.e2e.yml"
SUITE="${DCA_E2E_SUITE:-smoke}"

DOWNLOAD_PROXY="${HTTP_PROXY:-${HTTPS_PROXY:-http://127.0.0.1:7890}}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
export no_proxy="${no_proxy:-$NO_PROXY}"
export PLAYWRIGHT_CHROMIUM_USE_HEADLESS_SHELL=0

if ! command -v docker >/dev/null 2>&1; then
    printf 'docker is required.\n' >&2
    exit 2
fi
if ! command -v npm >/dev/null 2>&1; then
    printf 'npm is required.\n' >&2
    exit 2
fi

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
PROJECT="dca-e2e-$RUN_ID"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dca-terminal-e2e.XXXXXX")"
ENV_FILE="$TEMP_DIR/e2e.env"

cleanup() {
    local status="$?"
    set +e
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$OVERRIDE_FILE" down -v --remove-orphans >/dev/null 2>&1
    rm -rf -- "$TEMP_DIR"
    exit "$status"
}
trap cleanup EXIT

umask 077
printf '%s\n' \
    "COMPOSE_PROJECT_NAME=$PROJECT" \
    'APP_DOMAIN=localhost' \
    'CADDY_EMAIL=e2e@example.invalid' \
    'POSTGRES_IMAGE=postgres:18.6-alpine' \
    'CADDY_IMAGE=caddy:2-alpine' \
    'POSTGRES_DB=dca_e2e' \
    'POSTGRES_USER=dca_e2e' \
    'POSTGRES_PASSWORD=sa06-temporary-postgres-password' \
    'APP_USERNAME=e2e' \
    'APP_PASSWORD_HASH=$$2b$$10$$N9qo8uLOickgx2ZMRZoMyeyWZ98aNASLjNbtXyQqlJDPam8SBgRkC' \
    'APP_SECURITY_ENABLED=true' \
    'APP_COOKIE_SECURE=false' \
    'APP_LOGIN_MAX_ATTEMPTS=5' \
    'APP_LOGIN_THROTTLE_WINDOW_SECONDS=900' \
    'SPRING_PROFILES_ACTIVE=prod' \
    'FLYWAY_ENABLED=true' \
    'MARKET_SYNC_ENABLED=false' \
    'VITE_API_BASE_URL=/api/v1' \
    'VITE_APP_MODE=live' \
    'MARKET_DATA_PRIMARY_PROVIDER=YAHOO' \
    'MARKET_DATA_FALLBACK_PROVIDER=YAHOO' \
    'YAHOO_BASE_URL=http://mock-yahoo:8080' \
    'YAHOO_PROXY_URL=' \
    'TWELVE_DATA_API_KEY=' \
    'ALPHA_VANTAGE_API_KEY=' >"$ENV_FILE"

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$OVERRIDE_FILE")
"${COMPOSE[@]}" config --quiet

# GitHub-hosted runners start with an unpredictable Docker image cache. Pull
# the image-backed E2E services explicitly, then keep `up --pull never` so the
# actual stack startup is deterministic and cannot silently replace images.
"${COMPOSE[@]}" pull postgres caddy mock-yahoo
"${COMPOSE[@]}" up -d --build --pull never

wait_http() {
    local url="$1"
    local label="$2"
    local needle="${3:-}"
    local body
    for ((attempt = 1; attempt <= 90; attempt++)); do
        body="$(curl --silent --show-error --fail --max-time 5 --noproxy '*' "$url" || true)"
        if [[ -n "$body" && ( -z "$needle" || "$body" == *"$needle"* ) ]]; then
            return
        fi
        sleep 2
    done
    printf '%s did not become reachable: %s\n' "$label" "$url" >&2
    "${COMPOSE[@]}" ps >&2 || true
    "${COMPOSE[@]}" logs --no-color --tail=80 api >&2 || true
    "${COMPOSE[@]}" logs --no-color --tail=80 mock-yahoo >&2 || true
    "${COMPOSE[@]}" logs --no-color --tail=80 caddy >&2 || true
    exit 1
}

wait_http 'http://127.0.0.1:38081/health' 'mock provider' '"status":"ok"'
wait_http 'http://127.0.0.1:38080/api/health' 'Caddy API health' '"status":"UP"'

cd "$SCRIPT_DIR"
if [[ "${CI:-}" != true ]]; then
    export HTTP_PROXY="$DOWNLOAD_PROXY"
    export HTTPS_PROXY="$DOWNLOAD_PROXY"
    export http_proxy="$DOWNLOAD_PROXY"
    export https_proxy="$DOWNLOAD_PROXY"
    export NODE_USE_ENV_PROXY=1
fi
if [[ ! -d node_modules ]]; then
    npm ci
fi
if [[ "${CI:-}" == true ]]; then
    npx playwright install --with-deps --no-shell chromium
else
    npx playwright install --no-shell chromium
fi
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy NODE_USE_ENV_PROXY

export DCA_E2E_BASE_URL='http://127.0.0.1:38080'
export DCA_E2E_MOCK_URL='http://127.0.0.1:38081'
export DCA_E2E_USERNAME='e2e'
export DCA_E2E_PASSWORD='sa08-ci-password'
export DCA_E2E_SUITE="$SUITE"
export PLAYWRIGHT_OUTPUT_DIR="${PLAYWRIGHT_OUTPUT_DIR:-$TEMP_DIR/playwright-results}"

npx playwright test
printf 'E2E %s suite passed.\n' "$SUITE"
