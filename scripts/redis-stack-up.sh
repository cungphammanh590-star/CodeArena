#!/usr/bin/env bash
# 本机启动 redis-stack（含 RedisJSON），默认 :6380，不与 Homebrew 普通 redis(:6379) 抢端口。
# 端口：REDIS_PORT / REDIS_STACK_PORT（优先后者），默认 6380。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

PORT="${REDIS_STACK_PORT:-${REDIS_PORT:-6380}}"
HOST="${REDIS_HOST:-127.0.0.1}"

if ! [[ "$PORT" =~ ^[0-9]+$ ]]; then
  echo "invalid REDIS_PORT/REDIS_STACK_PORT: $PORT" >&2
  exit 1
fi

cli() {
  redis-cli -h "$HOST" -p "$PORT" "$@"
}

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  if cli JSON.SET __codearena_probe__ '$' '{}' >/dev/null 2>&1; then
    cli JSON.DEL __codearena_probe__ >/dev/null 2>&1 || true
    echo "redis-stack already running on :$PORT (JSON.SET ok)"
    exit 0
  fi
  echo "port $PORT busy but RedisJSON unavailable; refusing to kill foreign process." >&2
  echo "Free :$PORT or set REDIS_STACK_PORT to another free port." >&2
  exit 1
fi

if ! command -v redis-stack-server >/dev/null 2>&1; then
  echo "redis-stack-server not found. Install: brew install --cask redis-stack-server" >&2
  exit 1
fi

# 不改动 Homebrew 全局 conf；命令行 --port 覆盖默认 6379
nohup redis-stack-server --port "$PORT" >>/tmp/redis-stack.log 2>&1 &
sleep 2

cli PING
cli JSON.SET __codearena_probe__ '$' '{}'
cli JSON.DEL __codearena_probe__ >/dev/null
echo "redis-stack ready on :$PORT"
echo "Apps should use REDIS_URL=redis://$HOST:$PORT/0"
