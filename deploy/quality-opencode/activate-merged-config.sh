#!/usr/bin/env bash
set -euo pipefail

environment_file=/etc/dataagent-quality-opencode.env
temporary_file="$(mktemp)"
trap 'rm -f "$temporary_file"' EXIT

grep -v '^OPENCODE_CONFIG=' "$environment_file" | grep -v '^OPENCODE_CONFIG_DIR=' >"$temporary_file"
install -m 600 "$temporary_file" "$environment_file"
rm -f /data/dataagent-quality-opencode/runtime/opencode-provider.json
systemctl restart dataagent-quality-opencode.service
