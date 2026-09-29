#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=/etc/dataagent-quality-opencode.env
MYSQL_CONTAINER=normal-mysql

row="$(docker exec "$MYSQL_CONTAINER" sh -c \
  'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B -e '\''select base_url, api_key, model_name from saa_data_agent.model_config where is_active = 1 and is_deleted = 0 and model_type = "CHAT" order by updated_time desc limit 1'\''')"

IFS=$'\t' read -r base_url api_key model_id <<<"$row"
if [[ -z "$base_url" || -z "$api_key" || -z "$model_id" ]]; then
  echo "No active platform chat model configuration was found" >&2
  exit 1
fi

temporary="$(mktemp)"
trap 'rm -f "$temporary"' EXIT
grep -v '^DATA_AGENT_QUALITY_PROVIDER_' "$ENV_FILE" >"$temporary" || true
{
  printf 'DATA_AGENT_QUALITY_PROVIDER_BASE_URL=%s\n' "$base_url"
  printf 'DATA_AGENT_QUALITY_PROVIDER_API_KEY=%s\n' "$api_key"
  printf 'DATA_AGENT_QUALITY_PROVIDER_MODEL_ID=%s\n' "$model_id"
} >>"$temporary"
install -m 600 "$temporary" "$ENV_FILE"
systemctl restart dataagent-quality-opencode.service
