#!/usr/bin/env bash
# Plan §9.4 — Postgres backup script.
#
# Runs `pg_dump` against the running compose stack's `postgres` service
# and writes a gzipped dump to ./backups/lob-YYYYMMDD-HHMMSS.sql.gz.
#
# Usage:
#   make backup          # via the repo Makefile target
#   ./infra/scripts/backup.sh     # invoked directly
#
# Pre-conditions:
#   - `docker compose ps postgres` shows the postgres service Running.
#   - $POSTGRES_USER + $POSTGRES_PASSWORD + $POSTGRES_DB resolved from
#     `.env` (defaults: lob/lob/lob), matching docker-compose.yml.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# Source .env if present so we don't hard-code secrets here.
if [[ -f .env ]]; then
    # shellcheck disable=SC1091  # only sourced in dev; CI sets env directly
    set -a; source .env; set +a
fi

POSTGRES_USER="${POSTGRES_USER:-lob}"
POSTGRES_DB="${POSTGRES_DB:-lob}"

OUT_DIR="$REPO_ROOT/backups"
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="$OUT_DIR/lob-$TS.sql.gz"

echo "==> dumping $POSTGRES_DB from compose service 'postgres' to $OUT_FILE"

# `--clean --if-exists` makes the dump self-contained for restore:
# `gunzip -c lob-*.sql.gz | psql -U lob -d lob` drops + recreates objects.
docker compose exec -T postgres \
    pg_dump \
        --username="$POSTGRES_USER" \
        --dbname="$POSTGRES_DB" \
        --clean --if-exists \
    | gzip --best > "$OUT_FILE"

ls -lh "$OUT_FILE"
echo "==> backup complete"
