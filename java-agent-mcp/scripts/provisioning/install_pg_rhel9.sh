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

run() { echo "+ $*" >&2; "$@" || { echo "FAILED (exit $?): $*" >&2; exit 1; }; }

PGDG_REPO="https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
PKG="postgresql${PG_MAJOR}-server"
CLIENT="postgresql${PG_MAJOR}"

run $SUDO dnf install -y "$PGDG_REPO"
run $SUDO dnf -qy module disable postgresql || true
run $SUDO dnf install -y "$PKG" "$CLIENT"
run $SUDO "/usr/pgsql-${PG_MAJOR}/bin/postgresql-${PG_MAJOR}-setup" initdb || true
run $SUDO systemctl enable "postgresql-${PG_MAJOR}"
run $SUDO systemctl start "postgresql-${PG_MAJOR}"
echo "PostgreSQL ${PG_MAJOR} installed (RHEL 9)" >&2
