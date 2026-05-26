#!/usr/bin/env bash
set -euo pipefail
EXT_NAME="${EXT_NAME:?}"
DB_NAME="${DB_NAME:?}"
PSQL="sudo -u postgres psql -d ${DB_NAME} -v ON_ERROR_STOP=1"

avail=$($PSQL -tAc "SELECT 1 FROM pg_available_extensions WHERE name='${EXT_NAME}'" 2>/dev/null || echo "0")
if [ "$avail" != "1" ]; then
  case "$EXT_NAME" in
    pg_stat_statements)
      sudo apt-get install -y postgresql-18-contrib 2>/dev/null || sudo dnf install -y postgresql18-contrib 2>/dev/null || true
      ;;
    pg_profile)
      sudo apt-get install -y pg-profile-postgresql18 2>/dev/null || true
      ;;
  esac
fi

has=$($PSQL -tAc "SELECT 1 FROM pg_extension WHERE extname='${EXT_NAME}'" 2>/dev/null || echo "0")
if [ "$has" = "1" ]; then
  printf '%s\n' "{\"skipped\":true,\"installed\":true}"
  exit 0
fi

if [ "$EXT_NAME" = "auto_explain" ]; then
  PG_MAJOR="${PG_MAJOR:-18}"
  CONF=""
  if [ -f "/etc/postgresql/${PG_MAJOR}/main/postgresql.conf" ]; then
    CONF="/etc/postgresql/${PG_MAJOR}/main/postgresql.conf"
  else
    CONF=$(sudo find /etc/postgresql /var/lib/pgsql -name postgresql.conf 2>/dev/null | head -1)
  fi
  if [ -n "$CONF" ] && [ -f "$CONF" ]; then
    sudo sed -i "s/^#*shared_preload_libraries.*/shared_preload_libraries = 'auto_explain'/" "$CONF" || \
      echo "shared_preload_libraries = 'auto_explain'" | sudo tee -a "$CONF"
    sudo systemctl reload postgresql 2>/dev/null || sudo systemctl reload postgresql-18 2>/dev/null || true
  fi
fi

$PSQL -c "CREATE EXTENSION IF NOT EXISTS ${EXT_NAME};" 2>/dev/null && \
  printf '%s\n' "{\"skipped\":false,\"installed\":true}" || \
  printf '%s\n' "{\"skipped\":false,\"installed\":false}"
