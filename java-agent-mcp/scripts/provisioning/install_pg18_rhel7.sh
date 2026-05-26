#!/usr/bin/env bash
set -uo pipefail
echo "ERROR: PostgreSQL 18 is not supported on RHEL/CentOS 7." >&2
echo "Use RHEL 8/9, Rocky 8/9, AlmaLinux 8/9, or Ubuntu 22.04/24.04 instead." >&2
echo "PGDG EL-9 packages cannot be installed on EL-7 (rpmlib PayloadIsZstd mismatch)." >&2
exit 1
