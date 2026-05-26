#!/usr/bin/env bash
set -euo pipefail
DB_NAME="${DB_NAME:?}"
SCHEMA_NAME="${SCHEMA_NAME:?}"
OWNER="${OWNER:-${DB_NAME}_owner}"
OWNER_PASSWORD="${OWNER_PASSWORD:-}"
PG_MAJOR="${PG_MAJOR:-18}"
SERVER_HOST="${SERVER_HOST:-127.0.0.1}"

PSQL="sudo -u postgres psql -v ON_ERROR_STOP=1"
REMOTE_CIDR="${REMOTE_CIDR:-0.0.0.0/0}"

sql_literal() {
  printf "%s" "$1" | sed "s/'/''/g"
}

find_pg_file() {
  local setting="$1"
  local file
  file=$($PSQL -tAc "SHOW ${setting}" 2>/dev/null | xargs || true)
  if [ -n "$file" ] && [ -f "$file" ]; then
    echo "$file"
    return 0
  fi
  return 1
}

ensure_line() {
  local file="$1"
  local line="$2"
  if sudo grep -qxF "$line" "$file" 2>/dev/null; then
    echo "pg_hba already contains: ${line}" >&2
    return 0
  fi
  echo "$line" | sudo tee -a "$file" >/dev/null
  echo "Added pg_hba rule: ${line}" >&2
}

restart_or_reload_postgres() {
  local restart_required="${1:-false}"
  if [ "$restart_required" = "true" ]; then
    if systemctl list-units --full --all 2>/dev/null | grep -q "postgresql@${PG_MAJOR}-main.service"; then
      sudo systemctl restart "postgresql@${PG_MAJOR}-main"
      return 0
    fi
    if systemctl list-unit-files 2>/dev/null | grep -q '^postgresql.service'; then
      sudo systemctl restart postgresql
      return 0
    fi
  fi
  $PSQL -c "SELECT pg_reload_conf();" >/dev/null || sudo systemctl reload postgresql 2>/dev/null || true
}

configure_remote_access() {
  local config_file hba_file restart_required=false
  config_file=$(find_pg_file "config_file" || true)
  hba_file=$(find_pg_file "hba_file" || true)

  if [ -z "$hba_file" ]; then
    echo "WARN: pg_hba.conf not found; remote client access was not configured" >&2
    return 0
  fi

  if [ -n "$config_file" ]; then
    current_listen=$($PSQL -tAc "SHOW listen_addresses" 2>/dev/null | xargs || true)
    if [ "$current_listen" != "*" ]; then
      sudo sed -i "s/^[#[:space:]]*listen_addresses[[:space:]]*=.*/listen_addresses = '*'/" "$config_file"
      if ! sudo grep -Eq "^[[:space:]]*listen_addresses[[:space:]]*=" "$config_file"; then
        echo "listen_addresses = '*'" | sudo tee -a "$config_file" >/dev/null
      fi
      restart_required=true
      echo "Configured listen_addresses='*' in ${config_file}" >&2
    fi
  else
    echo "WARN: postgresql.conf not found; listen_addresses was not configured" >&2
  fi

  ensure_line "$hba_file" "hostssl ${DB_NAME} ${OWNER} ${REMOTE_CIDR} scram-sha-256"
  ensure_line "$hba_file" "host    ${DB_NAME} ${OWNER} ${REMOTE_CIDR} scram-sha-256"
  restart_or_reload_postgres "$restart_required"
}

exists=$($PSQL -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" 2>/dev/null || echo "")
if [ "$exists" != "1" ]; then
  $PSQL -c "CREATE DATABASE ${DB_NAME} ENCODING 'UTF8' TEMPLATE template0;"
fi

role_exists=$($PSQL -tAc "SELECT 1 FROM pg_roles WHERE rolname='${OWNER}'" 2>/dev/null || echo "")
if [ "$role_exists" != "1" ]; then
  if [ -n "$OWNER_PASSWORD" ]; then
    escaped_password="$(sql_literal "$OWNER_PASSWORD")"
    $PSQL -c "CREATE ROLE ${OWNER} LOGIN PASSWORD '${escaped_password}';"
  else
    $PSQL -c "CREATE ROLE ${OWNER} LOGIN;"
  fi
elif [ -n "$OWNER_PASSWORD" ]; then
  escaped_password="$(sql_literal "$OWNER_PASSWORD")"
  $PSQL -c "ALTER ROLE ${OWNER} WITH LOGIN PASSWORD '${escaped_password}';"
fi

$PSQL -d "$DB_NAME" -c "CREATE SCHEMA IF NOT EXISTS ${SCHEMA_NAME};"
$PSQL -d "$DB_NAME" -c "GRANT ALL ON SCHEMA ${SCHEMA_NAME} TO ${OWNER};"
$PSQL -d "$DB_NAME" -c "ALTER DEFAULT PRIVILEGES IN SCHEMA ${SCHEMA_NAME} GRANT ALL ON TABLES TO ${OWNER};"
$PSQL -c "GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${OWNER};"

configure_remote_access

if [ -n "$OWNER_PASSWORD" ]; then
  hint="postgresql://${OWNER}@${SERVER_HOST}:5432/${DB_NAME}"
else
  hint="postgresql://${OWNER}@${SERVER_HOST}:5432/${DB_NAME} (set DB owner password before TCP login)"
fi
printf '%s\n' "{\"owner\":\"${OWNER}\",\"connectionHint\":\"${hint}\"}"
