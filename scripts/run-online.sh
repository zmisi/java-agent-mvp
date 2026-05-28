#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "Error: DASHSCOPE_API_KEY is required for online profile."
  exit 1
fi

export SPRING_PROFILES_ACTIVE=online
export DASHSCOPE_CHAT_MODEL="${DASHSCOPE_CHAT_MODEL:-qwen-plus}"
export DASHSCOPE_EMBEDDING_MODEL="${DASHSCOPE_EMBEDDING_MODEL:-text-embedding-v4}"
export AGENT_UI_DB_PASSWORD=P@ssword123
export AGENT_UI_DB_USER=dbagent

echo "Starting app with online profile..."
echo "  DASHSCOPE_CHAT_MODEL=$DASHSCOPE_CHAT_MODEL"
echo "  DASHSCOPE_EMBEDDING_MODEL=$DASHSCOPE_EMBEDDING_MODEL"

./mvnw spring-boot:run
