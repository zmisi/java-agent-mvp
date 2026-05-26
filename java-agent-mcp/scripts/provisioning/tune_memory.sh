#!/usr/bin/env bash
set -euo pipefail
MEMORY_MB="${MEMORY_MB:-512}"
PG_MAJOR="${PG_MAJOR:-18}"

if [ "$(id -u)" -eq 0 ]; then SUDO=""; else
  SUDO="sudo -n"
fi

SHARED=$(( MEMORY_MB / 4 ))
if [ "$SHARED" -lt 128 ]; then SHARED=128; fi
CACHE=$(( MEMORY_MB / 2 ))

find_postgresql_conf() {
  local c=""
  # Debian/Ubuntu PGDG (config under /etc or data dir)
  for c in \
    "/etc/postgresql/${PG_MAJOR}/main/postgresql.conf" \
    "/var/lib/postgresql/${PG_MAJOR}/main/postgresql.conf"; do
    if [ -f "$c" ]; then
      echo "$c"
      return 0
    fi
  done
  # Any version under /etc/postgresql
  if [ -d /etc/postgresql ]; then
    c=$($SUDO find /etc/postgresql -name postgresql.conf 2>/dev/null | head -1)
    if [ -n "$c" ] && [ -f "$c" ]; then
      echo "$c"
      return 0
    fi
  fi
  # RHEL layout
  c="/var/lib/pgsql/${PG_MAJOR}/data/postgresql.conf"
  if [ -f "$c" ]; then
    echo "$c"
    return 0
  fi
  if [ -d /var/lib/pgsql ]; then
    c=$($SUDO find /var/lib/pgsql -name postgresql.conf 2>/dev/null | head -1)
    if [ -n "$c" ] && [ -f "$c" ]; then
      echo "$c"
      return 0
    fi
  fi
  # Running server knows the path
  if command -v psql >/dev/null 2>&1; then
    c=$($SUDO -u postgres psql -tAc "SHOW config_file" 2>/dev/null | tr -d '[:space:]')
    if [ -n "$c" ] && [ -f "$c" ]; then
      echo "$c"
      return 0
    fi
  fi
  return 1
}

CONF=""
if ! CONF=$(find_postgresql_conf); then
  echo "postgresql.conf not found (PG_MAJOR=${PG_MAJOR})" >&2
  echo "Checked: /etc/postgresql/${PG_MAJOR}/main, /etc/postgresql/*, /var/lib/pgsql/${PG_MAJOR}/data" >&2
  if command -v pg_createcluster >/dev/null 2>&1; then
    echo "Hint: run: sudo pg_createcluster ${PG_MAJOR} main --start" >&2
  fi
  exit 1
fi

echo "Using config: ${CONF}" >&2
$SUDO sed -i "s/^#*shared_buffers.*/shared_buffers = ${SHARED}MB/" "$CONF" \
  || echo "shared_buffers = ${SHARED}MB" | $SUDO tee -a "$CONF"
$SUDO sed -i "s/^#*effective_cache_size.*/effective_cache_size = ${CACHE}MB/" "$CONF" \
  || echo "effective_cache_size = ${CACHE}MB" | $SUDO tee -a "$CONF"

reload_service() {
  if $SUDO systemctl is-active postgresql >/dev/null 2>&1; then
    $SUDO systemctl reload postgresql || $SUDO systemctl restart postgresql
  elif $SUDO systemctl is-active "postgresql-${PG_MAJOR}" >/dev/null 2>&1; then
    $SUDO systemctl reload "postgresql-${PG_MAJOR}" || $SUDO systemctl restart "postgresql-${PG_MAJOR}"
  elif [ -d "/etc/postgresql/${PG_MAJOR}/main" ]; then
    $SUDO pg_ctlcluster "${PG_MAJOR}" main reload 2>/dev/null \
      || $SUDO pg_ctlcluster "${PG_MAJOR}" main restart 2>/dev/null \
      || true
  fi
}
reload_service
echo "tuned shared_buffers=${SHARED}MB effective_cache_size=${CACHE}MB" >&2
