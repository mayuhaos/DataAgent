#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

: "${DATA_AGENT_BACKEND_IMAGE:?Set DATA_AGENT_BACKEND_IMAGE to the full ACR image reference.}"
DATA_AGENT_CONFIG_FILE="${DATA_AGENT_CONFIG_FILE:-$SCRIPT_DIR/config/application.yml}"

if [[ ! -f "$DATA_AGENT_CONFIG_FILE" ]]; then
  echo "Configuration file does not exist: $DATA_AGENT_CONFIG_FILE" >&2
  exit 1
fi

if [[ -n "${ACR_USERNAME:-}" || -n "${ACR_PASSWORD:-}" ]]; then
  : "${ACR_REGISTRY:?Set ACR_REGISTRY when using ACR_USERNAME and ACR_PASSWORD.}"
  : "${ACR_USERNAME:?Set ACR_USERNAME.}"
  : "${ACR_PASSWORD:?Set ACR_PASSWORD.}"
  printf '%s' "$ACR_PASSWORD" | docker login "$ACR_REGISTRY" --username "$ACR_USERNAME" --password-stdin
fi

export DATA_AGENT_BACKEND_IMAGE DATA_AGENT_CONFIG_FILE
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" pull backend
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" up -d --no-build backend
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" ps backend
