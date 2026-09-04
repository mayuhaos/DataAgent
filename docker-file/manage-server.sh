#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$SCRIPT_DIR/docker-compose-server.yml}"
SERVICE_NAME="data-agent-backend"

if ! command -v docker >/dev/null 2>&1; then
  echo "错误：未安装 Docker。" >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE_COMMAND=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_COMMAND=(docker-compose)
else
  echo "错误：未安装 Docker Compose（docker compose 或 docker-compose）。" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "错误：Compose 文件不存在：$COMPOSE_FILE" >&2
  exit 1
fi

compose() {
  "${COMPOSE_COMMAND[@]}" --project-directory "$SCRIPT_DIR" -f "$COMPOSE_FILE" "$@"
}

start_service() {
  compose up -d "$SERVICE_NAME"
  compose ps "$SERVICE_NAME"
}

stop_service() {
  compose stop "$SERVICE_NAME"
  compose ps "$SERVICE_NAME"
}

restart_service() {
  compose restart "$SERVICE_NAME"
  compose ps "$SERVICE_NAME"
}

update_service() {
  compose pull "$SERVICE_NAME"
  compose up -d --force-recreate "$SERVICE_NAME"
  compose ps "$SERVICE_NAME"
}

show_logs() {
  compose logs --tail=200 -f "$SERVICE_NAME"
}

while true; do
  cat <<'EOF'

Data Agent 后端管理
1. 启动服务
2. 停止服务
3. 重启服务
4. 拉取最新镜像并部署
5. 查看实时日志
0. 退出
EOF

  read -r -p "请选择操作 [0-5]：" choice

  case "$choice" in
    1) start_service ;;
    2) stop_service ;;
    3) restart_service ;;
    4) update_service ;;
    5) show_logs ;;
    0)
      echo "已退出。"
      exit 0
      ;;
    *) echo "无效选项，请输入 0-5。" ;;
  esac
done
