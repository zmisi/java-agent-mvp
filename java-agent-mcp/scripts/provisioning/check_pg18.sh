#!/usr/bin/env bash
set -euo pipefail
major=0
installed=false
if command -v psql >/dev/null 2>&1; then
  ver=$(psql --version 2>/dev/null | awk '{print $3}' | cut -d. -f1)
  major=${ver:-0}
  if [ "$major" -ge 18 ] 2>/dev/null; then
    installed=true
  fi
elif command -v postgres >/dev/null 2>&1; then
  ver=$(postgres --version 2>/dev/null | awk '{print $3}' | cut -d. -f1)
  major=${ver:-0}
  if [ "$major" -ge 18 ] 2>/dev/null; then
    installed=true
  fi
fi
printf '%s\n' "{\"installed\":${installed},\"majorVersion\":${major}}"
