#!/usr/bin/env bash
set -euo pipefail
DISK_GB="${DISK_GB:-10}"
DATA_DIR="${DATA_DIR:-/var/lib/postgresql}"
avail_kb=$(df -Pk "$DATA_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -z "$avail_kb" ]; then
  avail_kb=$(df -Pk / | awk 'NR==2 {print $4}')
fi
avail_gb=$(( avail_kb / 1024 / 1024 ))
ok=false
if [ "$avail_gb" -ge "$DISK_GB" ]; then ok=true; fi
printf '%s\n' "{\"ok\":${ok},\"availableGb\":${avail_gb}}"
