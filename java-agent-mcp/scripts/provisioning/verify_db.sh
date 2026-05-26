#!/usr/bin/env bash
set -euo pipefail
DB_NAME="${DB_NAME:?}"
OWNER="${OWNER:-}"
OWNER_PASSWORD="${OWNER_PASSWORD:-}"

if [ -n "$OWNER" ] && [ -n "$OWNER_PASSWORD" ]; then
  echo "Verifying TCP password login for ${OWNER}@127.0.0.1/${DB_NAME}" >&2
  ver=$(PGPASSWORD="$OWNER_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$OWNER" -d "$DB_NAME" -tAc "SELECT version();" 2>/tmp/provision_verify_db.err | head -1 | tr -d '\n' || true)
else
  echo "Verifying database as postgres via local administrative socket" >&2
  ver=$(sudo -u postgres psql -d "$DB_NAME" -tAc "SELECT version();" 2>/tmp/provision_verify_db.err | head -1 | tr -d '\n' || true)
fi

if [ -n "$ver" ]; then
  printf '%s\n' "{\"ok\":true,\"serverVersion\":\"${ver}\"}"
else
  err=$(tail -20 /tmp/provision_verify_db.err 2>/dev/null | tr '\n' ' ' | sed 's/"/\\"/g')
  printf '%s\n' "{\"ok\":false,\"error\":\"${err}\"}"
  exit 1
fi
