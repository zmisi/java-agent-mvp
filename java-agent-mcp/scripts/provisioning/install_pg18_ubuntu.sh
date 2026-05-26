#!/usr/bin/env bash
# Same as install_pg_ubuntu.sh; MCP prefers install_pg_ubuntu.sh with PG_MAJOR env.
export PG_MAJOR=18
exec "$(cd "$(dirname "$0")" && pwd)/install_pg_ubuntu.sh" 2>/dev/null || {
  echo "Run install_pg_ubuntu.sh with PG_MAJOR=18" >&2
  exit 1
}
