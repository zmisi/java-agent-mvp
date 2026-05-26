#!/usr/bin/env bash
set -uo pipefail

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  if ! $SUDO true 2>/dev/null; then
    echo "ERROR: passwordless sudo required (NOPASSWD) for provisioning user" >&2
    exit 1
  fi
fi

run() {
  echo "+ $*" >&2
  "$@" || { echo "FAILED (exit $?): $*" >&2; exit 1; }
}

PGDG_REPO="https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"

run $SUDO dnf install -y "$PGDG_REPO" || run $SUDO yum install -y "$PGDG_REPO"
run $SUDO dnf -qy module disable postgresql || run $SUDO yum -y module disable postgresql || true
run $SUDO dnf install -y postgresql18-server postgresql18 || run $SUDO yum install -y postgresql18-server postgresql18
run $SUDO /usr/pgsql-18/bin/postgresql-18-setup initdb 2>/dev/null || run $SUDO /usr/pgsql-18/bin/postgresql-18-setup initdb || true
run $SUDO systemctl enable postgresql-18
run $SUDO systemctl start postgresql-18
echo "PostgreSQL 18 installed (RHEL 8 family)" >&2
