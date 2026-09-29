#!/usr/bin/env bash
set -euo pipefail

source /etc/dataagent-quality-opencode.env
auth="${OPENCODE_SERVER_USERNAME}:${OPENCODE_SERVER_PASSWORD}"
session="$(curl -fsS -u "$auth" -H 'Content-Type: application/json' \
  -d '{"title":"quality-health-check"}' http://127.0.0.1:4096/session)"
session_id="$(printf '%s' "$session" | python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])')"

cleanup() {
  curl -fsS -u "$auth" -X DELETE "http://127.0.0.1:4096/session/$session_id" >/dev/null || true
}
trap cleanup EXIT

response="$(curl -fsS -u "$auth" -H 'Content-Type: application/json' \
  -d '{"agent":"quality-python-planner","parts":[{"type":"text","text":"Return legacy mode for an unsupported request. Available row fields: week, inspection_count."}]}' \
  "http://127.0.0.1:4096/session/$session_id/message")"

printf '%s' "$response" | grep -Eo 'legacy|recipe' | head -1
