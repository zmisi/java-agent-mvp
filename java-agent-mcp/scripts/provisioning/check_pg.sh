#!/usr/bin/env bash
set -uo pipefail
TARGET_MAJOR="${PG_MAJOR:-0}"

echo "Checking PostgreSQL installation (target major: ${TARGET_MAJOR})" >&2

major=0
source_hint=""

read_major_from_bin() {
  local bin="$1"
  if [ -x "$bin" ] || command -v "$bin" >/dev/null 2>&1; then
    local ver
    ver=$("$bin" --version 2>/dev/null | awk '{print $3}' | cut -d. -f1)
    if [ -n "$ver" ] && [ "$ver" -gt 0 ] 2>/dev/null; then
      major="$ver"
      source_hint="$bin --version"
      return 0
    fi
  fi
  return 1
}

read_major_from_bin psql \
  || read_major_from_bin postgres \
  || read_major_from_bin "/usr/lib/postgresql/${TARGET_MAJOR}/bin/psql" \
  || read_major_from_bin "/usr/lib/postgresql/${TARGET_MAJOR}/bin/postgres" \
  || read_major_from_bin "/usr/pgsql-${TARGET_MAJOR}/bin/psql" \
  || read_major_from_bin "/usr/pgsql-${TARGET_MAJOR}/bin/postgres" \
  || true

if [ "$major" -eq 0 ] 2>/dev/null && command -v dpkg-query >/dev/null 2>&1; then
  pkg=$(dpkg-query -W -f='${Package} ${Status}\n' 'postgresql-*' 2>/dev/null \
    | awk '/install ok installed/ && $1 ~ /^postgresql-[0-9]+$/ {print $1}' \
    | sed 's/postgresql-//' \
    | sort -nr \
    | head -1)
  if [ -n "$pkg" ] && [ "$pkg" -gt 0 ] 2>/dev/null; then
    major="$pkg"
    source_hint="dpkg package postgresql-${pkg}"
  fi
fi

if [ "$major" -eq 0 ] 2>/dev/null && command -v rpm >/dev/null 2>&1; then
  pkg=$(rpm -qa 2>/dev/null \
    | sed -n 's/^postgresql\([0-9][0-9]*\)-server.*/\1/p' \
    | sort -nr \
    | head -1)
  if [ -n "$pkg" ] && [ "$pkg" -gt 0 ] 2>/dev/null; then
    major="$pkg"
    source_hint="rpm package postgresql${pkg}-server"
  fi
fi

installed=false
if [ "$TARGET_MAJOR" -gt 0 ] 2>/dev/null && [ "$major" -ge "$TARGET_MAJOR" ] 2>/dev/null; then
  installed=true
fi

if [ "$major" -gt 0 ] 2>/dev/null; then
  echo "Detected PostgreSQL major ${major} via ${source_hint}" >&2
else
  echo "No PostgreSQL binary/package detected yet; install step will run next" >&2
fi

printf '%s\n' "{\"installed\":${installed},\"majorVersion\":${major},\"targetMajor\":${TARGET_MAJOR},\"source\":\"${source_hint}\"}"
