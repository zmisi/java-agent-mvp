#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
  echo "Loaded environment from .env"
fi

if ! command -v ollama >/dev/null 2>&1; then
  echo "Error: ollama is not installed. Install it first: brew install ollama"
  exit 1
fi

if ! curl -sSf "${OLLAMA_BASE_URL:-http://127.0.0.1:11434}/api/version" >/dev/null 2>&1; then
  echo "Warning: Ollama service is not reachable at ${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
  echo "Start it with: ollama serve"
fi

export SPRING_PROFILES_ACTIVE=local
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
export OLLAMA_CHAT_MODEL="${OLLAMA_CHAT_MODEL:-qwen2.5:7b-instruct-q4_K_M}"
export OLLAMA_EMBEDDING_MODEL="${OLLAMA_EMBEDDING_MODEL:-nomic-embed-text}"
export AGENT_UI_DB_PASSWORD=P@ssword123
export AGENT_UI_DB_USER=dbagent

echo "Starting app with local profile..."
echo "  OLLAMA_BASE_URL=$OLLAMA_BASE_URL"
echo "  OLLAMA_CHAT_MODEL=$OLLAMA_CHAT_MODEL"
echo "  OLLAMA_EMBEDDING_MODEL=$OLLAMA_EMBEDDING_MODEL"

./mvnw spring-boot:run
