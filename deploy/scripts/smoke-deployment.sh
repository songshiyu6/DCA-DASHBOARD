#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

BASE_URL="${DCA_SMOKE_BASE_URL:-}"
USERNAME="${DCA_SMOKE_USERNAME:-}"
PASSWORD="${DCA_SMOKE_PASSWORD:-}"
EXPECT_SECURE_COOKIES="${DCA_SMOKE_EXPECT_SECURE_COOKIES:-1}"

if [[ -z "$BASE_URL" || -z "$USERNAME" || -z "$PASSWORD" ]]; then
    printf 'DCA_SMOKE_BASE_URL, DCA_SMOKE_USERNAME and DCA_SMOKE_PASSWORD are required.\n' >&2
    exit 2
fi
case "$BASE_URL" in
    http://*|https://*) ;;
    *) printf 'DCA_SMOKE_BASE_URL must use http:// or https://.\n' >&2; exit 2 ;;
esac
BASE_URL="${BASE_URL%/}"
if [[ "$EXPECT_SECURE_COOKIES" != 0 && "$EXPECT_SECURE_COOKIES" != 1 ]]; then
    printf 'DCA_SMOKE_EXPECT_SECURE_COOKIES must be 0 or 1.\n' >&2
    exit 2
fi

if ! command -v curl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
    printf 'curl and python3 are required.\n' >&2
    exit 2
fi

CURL_OPTIONS=(--silent --show-error --location --connect-timeout 5 --max-time 20)
if [[ "${DCA_SMOKE_INSECURE:-0}" == 1 && "$BASE_URL" == https://* ]]; then
    CURL_OPTIONS+=(--insecure)
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dca-terminal-deployment-smoke.XXXXXX")"
COOKIE_JAR="$TEMP_DIR/cookies.txt"
AUTH_COOKIE_JAR="$TEMP_DIR/authenticated-cookies.txt"
LOGIN_PAYLOAD="$TEMP_DIR/login.json"
trap 'rm -rf -- "$TEMP_DIR"' EXIT
umask 077

http_request() {
    local method="$1"
    local body_file="$2"
    local header_file="$3"
    shift 3
    curl "${CURL_OPTIONS[@]}" --request "$method" \
        --output "$body_file" --dump-header "$header_file" --write-out '%{http_code}' "$@"
}

expect_status() {
    local label="$1"
    local actual="$2"
    local expected="$3"
    if [[ "$actual" != "$expected" ]]; then
        printf '%s failed: expected HTTP %s, got HTTP %s.\n' "$label" "$expected" "$actual" >&2
        exit 1
    fi
}

assert_json_kind() {
    local body_file="$1"
    local kind="$2"
    python3 - "$body_file" "$kind" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)

kind = sys.argv[2]
if not isinstance(body, dict):
    raise SystemExit("response is not a JSON object")

if kind == "health":
    if body.get("status") != "UP":
        raise SystemExit("health status is not UP")
elif kind == "unauthenticated":
    if body.get("authenticated") is not False or body.get("username") is not None:
        raise SystemExit("session is unexpectedly authenticated")
elif kind == "authenticated":
    if body.get("authenticated") is not True:
        raise SystemExit("login did not authenticate")
else:
    raise SystemExit("unknown response assertion")
PY
}

assert_header_contains() {
    local header_file="$1"
    local name="$2"
    local expected="$3"
    python3 - "$header_file" "$name" "$expected" <<'PY'
import sys

header_file, name, expected = sys.argv[1:]
needle = name.lower() + ":"
with open(header_file, encoding="utf-8") as handle:
    value = next((line.split(":", 1)[1].strip() for line in handle
                  if line.lower().startswith(needle)), None)
if value is None:
    raise SystemExit(f"missing header {name}")
if expected not in value:
    raise SystemExit(f"header {name} mismatch")
PY
}

assert_header_absent() {
    local header_file="$1"
    local name="$2"
    python3 - "$header_file" "$name" <<'PY'
import sys

header_file, name = sys.argv[1:]
needle = name.lower() + ":"
with open(header_file, encoding="utf-8") as handle:
    if any(line.lower().startswith(needle) for line in handle):
        raise SystemExit(f"unexpected header {name}")
PY
}

assert_cookie_flags() {
    local header_file="$1"
    local cookie_name="$2"
    local expect_secure="$3"
    local expect_http_only="$4"
    python3 - "$header_file" "$cookie_name" "$expect_secure" "$expect_http_only" <<'PY'
import sys

header_file, cookie_name, expect_secure, expect_http_only = sys.argv[1:]
expect_secure = expect_secure == "1"
expect_http_only = expect_http_only == "1"

with open(header_file, encoding="utf-8") as handle:
    lines = [line.strip() for line in handle if line.lower().startswith("set-cookie:")]

cookie_line = next((line.split(":", 1)[1].strip() for line in lines
                    if line.split(":", 1)[1].strip().startswith(cookie_name + "=")), None)
if cookie_line is None:
    raise SystemExit("expected cookie was not set")

attributes = {part.strip().split("=", 1)[0].lower(): part.strip().split("=", 1)[1]
              if "=" in part.strip() else True
              for part in cookie_line.split(";")[1:]}
if expect_secure != ("secure" in attributes):
    raise SystemExit("cookie Secure flag mismatch")
if expect_http_only != ("httponly" in attributes):
    raise SystemExit("cookie HttpOnly flag mismatch")
if attributes.get("samesite", "").lower() != "lax":
    raise SystemExit("cookie SameSite flag mismatch")
PY
}

DCA_SMOKE_USERNAME="$USERNAME" DCA_SMOKE_PASSWORD="$PASSWORD" python3 - >"$LOGIN_PAYLOAD" <<'PY'
import json
import os

print(json.dumps({
    "username": os.environ["DCA_SMOKE_USERNAME"],
    "password": os.environ["DCA_SMOKE_PASSWORD"],
}))
PY

ROOT_BODY="$TEMP_DIR/root.body"
ROOT_HEADERS="$TEMP_DIR/root.headers"
status="$(http_request GET "$ROOT_BODY" "$ROOT_HEADERS" "$BASE_URL/")"
expect_status "Caddy root" "$status" 200
assert_header_contains "$ROOT_HEADERS" "X-Content-Type-Options" "nosniff"
assert_header_contains "$ROOT_HEADERS" "Referrer-Policy" "strict-origin-when-cross-origin"
assert_header_contains "$ROOT_HEADERS" "Permissions-Policy" "camera=()"
assert_header_contains "$ROOT_HEADERS" "Content-Security-Policy-Report-Only" "default-src 'self'"
assert_header_absent "$ROOT_HEADERS" "Content-Security-Policy"
if [[ "$BASE_URL" == https://* ]]; then
    assert_header_contains "$ROOT_HEADERS" "Strict-Transport-Security" "max-age="
else
    assert_header_absent "$ROOT_HEADERS" "Strict-Transport-Security"
fi

HEALTH_BODY="$TEMP_DIR/health.body"
HEALTH_HEADERS="$TEMP_DIR/health.headers"
status="$(http_request GET "$HEALTH_BODY" "$HEALTH_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/api/health")"
expect_status "API health" "$status" 200
assert_json_kind "$HEALTH_BODY" health

SESSION_BODY="$TEMP_DIR/session.body"
SESSION_HEADERS="$TEMP_DIR/session.headers"
status="$(http_request GET "$SESSION_BODY" "$SESSION_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/api/v1/auth/session")"
expect_status "unauthenticated session" "$status" 200
assert_json_kind "$SESSION_BODY" unauthenticated

CSRF_BODY="$TEMP_DIR/csrf.body"
CSRF_HEADERS="$TEMP_DIR/csrf.headers"
status="$(http_request GET "$CSRF_BODY" "$CSRF_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/api/v1/auth/csrf")"
expect_status "CSRF bootstrap" "$status" 200
assert_cookie_flags "$CSRF_HEADERS" XSRF-TOKEN "$EXPECT_SECURE_COOKIES" 0

IFS=$'\t' read -r CSRF_TOKEN CSRF_HEADER < <(python3 - "$CSRF_BODY" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)
token = body.get("token")
header = body.get("headerName")
if not isinstance(token, str) or not token or not isinstance(header, str) or not header:
    raise SystemExit("CSRF response is incomplete")
print(f"{token}\t{header}")
PY
)
if [[ ! "$CSRF_HEADER" =~ ^[A-Za-z0-9-]+$ ]]; then
    printf 'CSRF header name is invalid.\n' >&2
    exit 1
fi

LOGIN_BODY="$TEMP_DIR/login.body"
LOGIN_HEADERS="$TEMP_DIR/login.headers"
status="$(http_request POST "$LOGIN_BODY" "$LOGIN_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' -H "$CSRF_HEADER: $CSRF_TOKEN" \
    --data-binary "@$LOGIN_PAYLOAD" "$BASE_URL/api/v1/auth/login")"
expect_status "login" "$status" 200
assert_json_kind "$LOGIN_BODY" authenticated
assert_cookie_flags "$LOGIN_HEADERS" JSESSIONID "$EXPECT_SECURE_COOKIES" 1
cp -- "$COOKIE_JAR" "$AUTH_COOKIE_JAR"

SETTINGS_BODY="$TEMP_DIR/settings.body"
SETTINGS_HEADERS="$TEMP_DIR/settings.headers"
status="$(http_request GET "$SETTINGS_BODY" "$SETTINGS_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/api/v1/settings")"
expect_status "settings" "$status" 200

DASHBOARD_BODY="$TEMP_DIR/dashboard.body"
DASHBOARD_HEADERS="$TEMP_DIR/dashboard.headers"
status="$(http_request GET "$DASHBOARD_BODY" "$DASHBOARD_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/api/v1/dashboard")"
expect_status "dashboard" "$status" 200

LOGOUT_BODY="$TEMP_DIR/logout.body"
LOGOUT_HEADERS="$TEMP_DIR/logout.headers"
status="$(http_request POST "$LOGOUT_BODY" "$LOGOUT_HEADERS" \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H "$CSRF_HEADER: $CSRF_TOKEN" "$BASE_URL/api/v1/auth/logout")"
expect_status "logout" "$status" 204

OLD_SESSION_BODY="$TEMP_DIR/old-session.body"
OLD_SESSION_HEADERS="$TEMP_DIR/old-session.headers"
status="$(http_request GET "$OLD_SESSION_BODY" "$OLD_SESSION_HEADERS" \
    -b "$AUTH_COOKIE_JAR" "$BASE_URL/api/v1/dashboard")"
if [[ "$status" != 401 && "$status" != 403 ]]; then
    printf 'old session was not rejected after logout: HTTP %s.\n' "$status" >&2
    exit 1
fi

OLD_CSRF_BODY="$TEMP_DIR/old-csrf.body"
OLD_CSRF_HEADERS="$TEMP_DIR/old-csrf.headers"
status="$(http_request PUT "$OLD_CSRF_BODY" "$OLD_CSRF_HEADERS" \
    -b "$AUTH_COOKIE_JAR" \
    -H "$CSRF_HEADER: $CSRF_TOKEN" -H 'Content-Type: application/json' \
    --data '{}' "$BASE_URL/api/v1/settings")"
if [[ "$status" != 401 && "$status" != 403 ]]; then
    printf 'old CSRF/session mutation was not rejected after logout: HTTP %s.\n' "$status" >&2
    exit 1
fi

printf 'Deployment smoke passed: root, health, session, CSRF, login, settings, dashboard and logout.\n'
