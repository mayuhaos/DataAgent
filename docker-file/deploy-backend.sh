#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

: "${DATA_AGENT_BACKEND_IMAGE:?Set DATA_AGENT_BACKEND_IMAGE to the full ACR image reference.}"

if [[ -n "${ACR_USERNAME:-}" || -n "${ACR_PASSWORD:-}" ]]; then
  : "${ACR_REGISTRY:?Set ACR_REGISTRY when using ACR_USERNAME and ACR_PASSWORD.}"
  : "${ACR_USERNAME:?Set ACR_USERNAME.}"
  : "${ACR_PASSWORD:?Set ACR_PASSWORD.}"
  printf '%s' "$ACR_PASSWORD" | docker login "$ACR_REGISTRY" --username "$ACR_USERNAME" --password-stdin
fi

export DATA_AGENT_BACKEND_IMAGE
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" pull backend
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" up -d --no-build backend
docker compose --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" ps backend
