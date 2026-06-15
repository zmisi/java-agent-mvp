#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export EVAL_LIVE=1
mvn test -Peval-live "$@"
