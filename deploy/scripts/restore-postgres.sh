#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

usage() {
    printf 'Usage: %s --confirm BACKUP_FILE.sql.gz\n' "$0" >&2
    printf '\nThis replaces database objects from the selected pg_dump.\n' >&2
}

if [[ $# -ne 2 || "$1" != "--confirm" ]]; then
    usage
    exit 2
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$DEPLOY_DIR/.." && pwd)"
BACKUP_FILE="$2"
if [[ "$BACKUP_FILE" != /* ]]; then
    BACKUP_FILE="$REPO_ROOT/$BACKUP_FILE"
fi

if [[ ! -f "$BACKUP_FILE" ]]; then
    printf 'Backup file not found: %s\n' "$BACKUP_FILE" >&2
    exit 2
fi
if [[ "$BACKUP_FILE" != *.sql.gz ]]; then
    printf 'Only gzip-compressed plain SQL backups are accepted.\n' >&2
    exit 2
fi
if ! gzip -t "$BACKUP_FILE"; then
    printf 'Backup gzip validation failed: %s\n' "$BACKUP_FILE" >&2
    exit 2
fi

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
    printf 'PostgreSQL is not ready. Start the stack before restoring.\n' >&2
    exit 1
fi

if [[ "${DCA_RESTORE_SKIP_SAFETY_BACKUP:-0}" != "1" ]]; then
    printf 'Creating a safety backup before restore...\n'
    "$SCRIPT_DIR/backup-postgres.sh"
fi

printf 'Restoring %s into database %s.\n' "$BACKUP_FILE" "$POSTGRES_DB"
gunzip -c -- "$BACKUP_FILE" \
    | "${COMPOSE[@]}" exec -T postgres psql \
        --set=ON_ERROR_STOP=1 \
        --single-transaction \
        --username="$POSTGRES_USER" \
        --dbname="$POSTGRES_DB"

printf 'Restore completed. Rebuild projections and verify /api/health before serving traffic.\n'
