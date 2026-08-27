#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$DEPLOY_DIR/.." && pwd)"

ENV_FILE="${DCA_ENV_FILE:-$DEPLOY_DIR/.env}"
if [[ "$ENV_FILE" != /* ]]; then
    ENV_FILE="$REPO_ROOT/$ENV_FILE"
fi

if [[ ! -f "$ENV_FILE" ]]; then
    printf 'Environment file not found: %s\nCopy deploy/.env.example to deploy/.env first.\n' "$ENV_FILE" >&2
    exit 2
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.yml}"
if [[ "$COMPOSE_FILE" != /* ]]; then
    COMPOSE_FILE="$REPO_ROOT/$COMPOSE_FILE"
fi

POSTGRES_DB="${POSTGRES_DB:-dca_terminal}"
POSTGRES_USER="${POSTGRES_USER:-dca_terminal}"
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
if [[ "$BACKUP_DIR" != /* ]]; then
    BACKUP_DIR="$REPO_ROOT/$BACKUP_DIR"
fi

DAILY_RETENTION="${BACKUP_RETENTION_DAILY:-7}"
WEEKLY_RETENTION="${BACKUP_RETENTION_WEEKLY:-4}"
WEEKLY_DAY="${BACKUP_WEEKLY_DAY:-7}"

for value in "$DAILY_RETENTION" "$WEEKLY_RETENTION" "$WEEKLY_DAY"; do
    if [[ ! "$value" =~ ^[0-9]+$ ]]; then
        printf 'Backup retention values must be non-negative integers.\n' >&2
        exit 2
    fi
done
if (( WEEKLY_DAY < 1 || WEEKLY_DAY > 7 )); then
    printf 'BACKUP_WEEKLY_DAY must be between 1 and 7.\n' >&2
    exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
    printf 'docker is required.\n' >&2
    exit 2
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
    printf 'Compose file not found: %s\n' "$COMPOSE_FILE" >&2
    exit 2
fi

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
"${COMPOSE[@]}" config --quiet
if ! "${COMPOSE[@]}" exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null; then
    printf 'PostgreSQL is not ready. Start the stack before backing it up.\n' >&2
    exit 1
fi

umask 077
mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DAILY_FILE="$BACKUP_DIR/daily/dca-terminal-$TIMESTAMP.sql.gz"
TEMP_FILE="$(mktemp "$BACKUP_DIR/.dca-terminal-$TIMESTAMP.XXXXXX.sql.gz")"
cleanup() {
    rm -f -- "$TEMP_FILE"
}
trap cleanup EXIT

"${COMPOSE[@]}" exec -T postgres pg_dump \
    --dbname="$POSTGRES_DB" \
    --username="$POSTGRES_USER" \
    --format=plain \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    | gzip -9 >"$TEMP_FILE"

gzip -t "$TEMP_FILE"
mv -- "$TEMP_FILE" "$DAILY_FILE"

if [[ "$(date -u +%u)" == "$WEEKLY_DAY" ]]; then
    WEEKLY_FILE="$BACKUP_DIR/weekly/dca-terminal-$TIMESTAMP.sql.gz"
    cp -- "$DAILY_FILE" "$WEEKLY_FILE"
    chmod 600 "$WEEKLY_FILE"
fi

prune_backups() {
    local directory="$1"
    local prefix="$2"
    local keep="$3"
    local -a files=()
    local file

    while IFS= read -r -d '' file; do
        files+=("$file")
    done < <(find "$directory" -maxdepth 1 -type f -name "$prefix*.sql.gz" -print0 | sort -z -r)

    if (( ${#files[@]} <= keep )); then
        return
    fi
    for (( index = keep; index < ${#files[@]}; index++ )); do
        rm -f -- "${files[$index]}"
    done
}

prune_backups "$BACKUP_DIR/daily" "dca-terminal-" "$DAILY_RETENTION"
prune_backups "$BACKUP_DIR/weekly" "dca-terminal-" "$WEEKLY_RETENTION"

printf 'Backup created: %s\n' "$DAILY_FILE"
