#!/usr/bin/env bash
set -uo pipefail
PG_MAJOR="${PG_MAJOR:?PG_MAJOR required}"

if [ "$(id -u)" -eq 0 ]; then SUDO=""; else
  SUDO="sudo -n"
  if ! $SUDO true 2>/dev/null; then
    echo "ERROR: passwordless sudo required (NOPASSWD)" >&2
    exit 1
  fi
fi

# Wait up to 30 min (slow mirrors / in-flight install from a prior attempt).
APT_LOCK_MAX_WAIT="${APT_LOCK_MAX_WAIT:-1800}"
APT_OPTS=(
  -o "DPkg::Lock::Timeout=${APT_LOCK_MAX_WAIT}"
  -o "APT::Acquire::Retries=5"
  -o "Acquire::http::Timeout=60"
  -o "Acquire::https::Timeout=60"
  -o "Acquire::ftp::Timeout=60"
  -o "Dpkg::Use-Pty=0"
)
PGDG_APT_BASE_URL="${PGDG_APT_BASE_URL:-http://apt.postgresql.org/pub/repos/apt}"

export DEBIAN_FRONTEND=noninteractive

pkg_installed() {
  $SUDO dpkg-query -W -f='${Status}' "postgresql-${PG_MAJOR}" 2>/dev/null | grep -q "install ok installed"
}

pg_apt_install_in_progress() {
  $SUDO pgrep -af 'apt-get' 2>/dev/null | grep -qiE 'postgresql|postgresql-' && return 0
  $SUDO pgrep -af '/usr/bin/dpkg' 2>/dev/null | grep -qi postgresql && return 0
  return 1
}

apt_lock_held() {
  if command -v fuser >/dev/null 2>&1; then
    $SUDO fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 \
      || $SUDO fuser /var/lib/dpkg/lock >/dev/null 2>&1 \
      || $SUDO fuser /var/lib/apt/lists/lock >/dev/null 2>&1
    return $?
  fi
  if command -v lsof >/dev/null 2>&1; then
    $SUDO lsof /var/lib/dpkg/lock-frontend >/dev/null 2>&1 \
      || $SUDO lsof /var/lib/dpkg/lock >/dev/null 2>&1
    return $?
  fi
  return 1
}

wait_for_apt_idle() {
  local waited=0
  local interval=5
  while apt_lock_held || pg_apt_install_in_progress; do
    if [ "$waited" -ge "$APT_LOCK_MAX_WAIT" ]; then
      echo "ERROR: apt still busy after ${APT_LOCK_MAX_WAIT}s" >&2
      echo "Package manager processes:" >&2
      $SUDO ps -eo pid,comm,args 2>/dev/null | grep -E 'apt-get|apt |dpkg|unattended' | grep -v grep >&2 || true
      return 1
    fi
    if pg_apt_install_in_progress; then
      echo "Waiting (${waited}s): PostgreSQL apt install already running (prior attempt?):" >&2
      $SUDO pgrep -af 'apt-get|dpkg' 2>/dev/null | grep -i postgresql >&2 || true
    elif command -v fuser >/dev/null 2>&1; then
      echo "Waiting for apt lock (${waited}s) — holder(s):" >&2
      $SUDO fuser -v /var/lib/dpkg/lock-frontend 2>&1 | tail -5 >&2 || true
    else
      echo "Waiting for apt lock (${waited}s)…" >&2
    fi
    sleep "$interval"
    waited=$((waited + interval))
    if pkg_installed; then
      echo "PostgreSQL ${PG_MAJOR} became available while waiting — skipping new install" >&2
      return 0
    fi
  done
  return 0
}

pg_conf_exists() {
  [ -f "/etc/postgresql/${PG_MAJOR}/main/postgresql.conf" ] \
    || [ -f "/var/lib/postgresql/${PG_MAJOR}/main/postgresql.conf" ] \
    || [ -f "/var/lib/pgsql/${PG_MAJOR}/data/postgresql.conf" ]
}

ensure_pg_cluster() {
  if pg_conf_exists; then
    return 0
  fi

  if command -v pg_createcluster >/dev/null 2>&1; then
    echo "Creating cluster ${PG_MAJOR}/main via pg_createcluster…" >&2
    $SUDO pg_createcluster "${PG_MAJOR}" main --start
    pg_conf_exists && return 0
  fi

  local initdb="/usr/lib/postgresql/${PG_MAJOR}/bin/initdb"
  local pg_ctl="/usr/lib/postgresql/${PG_MAJOR}/bin/pg_ctl"
  local datadir="/var/lib/postgresql/${PG_MAJOR}/main"

  if [ ! -x "$initdb" ]; then
    echo "ERROR: PostgreSQL cluster missing; initdb not found at ${initdb}" >&2
    echo "Try: apt-get install -y postgresql-common postgresql-${PG_MAJOR}" >&2
    return 1
  fi

  echo "Initializing data directory ${datadir} (no pg_createcluster on this host)…" >&2
  $SUDO mkdir -p "$datadir" /var/log/postgresql
  $SUDO chown -R postgres:postgres /var/lib/postgresql /var/log/postgresql 2>/dev/null || true
  if ! id postgres >/dev/null 2>&1; then
    $SUDO useradd -r -s /bin/bash -d /var/lib/postgresql postgres 2>/dev/null || true
    $SUDO chown -R postgres:postgres /var/lib/postgresql /var/log/postgresql
  fi
  $SUDO -u postgres "$initdb" -D "$datadir"
  if [ -x "$pg_ctl" ]; then
    $SUDO -u postgres "$pg_ctl" -D "$datadir" \
      -l "/var/log/postgresql/postgresql-${PG_MAJOR}-main.log" start
  fi
  pg_conf_exists
}

ensure_pg_service() {
  if ! ensure_pg_cluster; then
    echo "ERROR: failed to create or locate PostgreSQL cluster" >&2
    exit 1
  fi
  if $SUDO systemctl list-unit-files 2>/dev/null | grep -q '^postgresql\.service'; then
    run $SUDO systemctl enable postgresql
    run $SUDO systemctl start postgresql
  elif $SUDO systemctl list-unit-files 2>/dev/null | grep -q "^postgresql-${PG_MAJOR}\.service"; then
    run $SUDO systemctl enable "postgresql-${PG_MAJOR}"
    run $SUDO systemctl start "postgresql-${PG_MAJOR}"
  elif command -v pg_ctlcluster >/dev/null 2>&1; then
    $SUDO pg_ctlcluster "${PG_MAJOR}" main start 2>/dev/null || true
  else
    echo "WARN: no postgresql systemd unit found; cluster may already be running" >&2
  fi
}

run() {
  echo "+ $*" >&2
  wait_for_apt_idle || exit 1
  if "$@"; then
    return 0
  fi
  local code=$?
  echo "FAILED (exit ${code}): $*" >&2
  if [ "$code" -eq 100 ] || [ "$code" -eq 1 ]; then
    echo "Retrying after apt becomes idle…" >&2
    sleep 15
    wait_for_apt_idle || exit 1
    if "$@"; then
      return 0
    fi
    code=$?
    echo "FAILED (exit ${code}) on retry: $*" >&2
  fi
  exit "$code"
}

require_package_candidate() {
  local pkg="$1"
  echo "+ apt-cache policy ${pkg}" >&2
  $SUDO apt-cache policy "$pkg" >&2 || true
  local candidate
  candidate=$($SUDO apt-cache policy "$pkg" 2>/dev/null | awk '/Candidate:/ {print $2; exit}')
  if [ -z "$candidate" ] || [ "$candidate" = "(none)" ]; then
    echo "ERROR: no apt candidate found for ${pkg}" >&2
    echo "Check OS codename, PGDG repo, and network access to ${PGDG_APT_BASE_URL}" >&2
    return 1
  fi
  echo "Found apt candidate for ${pkg}: ${candidate}" >&2
}

install_pgdg_key() {
  local keyring="/etc/apt/keyrings/postgresql.gpg"
  local tmp="/tmp/postgresql-pgdg-key.gpg"
  echo "+ curl PGDG key | gpg --dearmor --batch --yes" >&2
  curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    | $SUDO gpg --dearmor --batch --yes -o "$tmp"
  $SUDO install -m 0644 "$tmp" "$keyring"
  $SUDO rm -f "$tmp"
}

echo "=== PostgreSQL ${PG_MAJOR} install (Ubuntu/Debian) ===" >&2

wait_for_apt_idle || exit 1

if pkg_installed; then
  echo "PostgreSQL ${PG_MAJOR} packages already installed — skipping apt install" >&2
  ensure_pg_service
  echo "PostgreSQL ${PG_MAJOR} ready (Ubuntu/Debian)" >&2
  exit 0
fi

if pg_apt_install_in_progress; then
  echo "Another apt process is installing PostgreSQL — waiting for it to finish…" >&2
  wait_for_apt_idle || exit 1
  if pkg_installed; then
    echo "PostgreSQL ${PG_MAJOR} installed by concurrent apt — continuing" >&2
    ensure_pg_service
    echo "PostgreSQL ${PG_MAJOR} ready (Ubuntu/Debian)" >&2
    exit 0
  fi
fi

run $SUDO apt-get "${APT_OPTS[@]}" update -qq
run $SUDO apt-get "${APT_OPTS[@]}" install -y curl ca-certificates gnupg lsb-release
run $SUDO install -d -m 0755 /etc/apt/keyrings
install_pgdg_key
CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-$(lsb_release -cs)}")"
echo "deb [signed-by=/etc/apt/keyrings/postgresql.gpg] ${PGDG_APT_BASE_URL} ${CODENAME}-pgdg main" | $SUDO tee /etc/apt/sources.list.d/pgdg.list >/dev/null
run $SUDO apt-get "${APT_OPTS[@]}" update -qq
require_package_candidate "postgresql-${PG_MAJOR}" || exit 1

wait_for_apt_idle || exit 1
if pkg_installed; then
  echo "PostgreSQL ${PG_MAJOR} already installed before final apt install step" >&2
else
  run $SUDO apt-get "${APT_OPTS[@]}" install -y \
    postgresql-common "postgresql-${PG_MAJOR}" "postgresql-client-${PG_MAJOR}"
fi

ensure_pg_service
echo "PostgreSQL ${PG_MAJOR} installed (Ubuntu/Debian)" >&2
