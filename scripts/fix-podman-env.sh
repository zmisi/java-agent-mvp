#!/usr/bin/env bash
# One-time fix: Podman picking up Docker Desktop credential helper / external compose.
set -euo pipefail

DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker/config.json}"

if [[ -f "$DOCKER_CONFIG" ]]; then
  backup="${DOCKER_CONFIG}.bak.$(date +%Y%m%d%H%M%S)"
  cp "$DOCKER_CONFIG" "$backup"
  python3 - "$DOCKER_CONFIG" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    cfg = json.load(f)

changed = []
for key in ("credsStore", "credHelpers"):
    if key in cfg:
        del cfg[key]
        changed.append(f"removed {key}")

if cfg.get("currentContext", "").startswith("desktop"):
    del cfg["currentContext"]
    changed.append("removed desktop currentContext")

with open(path, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")

if changed:
    print("Updated", path, ":", ", ".join(changed))
else:
    print("No Docker Desktop credential settings in", path)
print("Backup:", path + ".bak.*")
PY
else
  echo "No $DOCKER_CONFIG — nothing to change."
fi

echo ""
echo "If ~/.zshrc contains PODMAN_COMPOSE_PROVIDER=podman, remove it (breaks compose)."
echo "Use the project wrapper instead:"
echo "  ./scripts/podman-compose.sh up --build"
