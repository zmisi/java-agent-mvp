#!/usr/bin/env bash
# Compose against Podman Machine via Docker API socket (not `podman up`).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export PATH="/opt/podman/bin:${PATH}"
unset PODMAN_COMPOSE_PROVIDER

socket="$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null || true)"
if [[ -z "$socket" ]]; then
  echo "error: Podman machine socket not found. Run: podman machine start" >&2
  exit 1
fi
export DOCKER_HOST="unix://${socket}"

compose_bin=""
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_bin="docker compose"
elif [[ -x "${HOME}/.docker/cli-plugins/docker-compose" ]]; then
  compose_bin="${HOME}/.docker/cli-plugins/docker-compose"
elif command -v docker-compose >/dev/null 2>&1; then
  compose_bin="docker-compose"
elif command -v podman-compose >/dev/null 2>&1; then
  exec podman-compose "$@"
else
  echo "error: install Docker Compose (Docker Desktop plugin) or: brew install podman-compose" >&2
  exit 1
fi

exec $compose_bin "$@"
