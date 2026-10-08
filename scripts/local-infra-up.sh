#!/usr/bin/env bash
# Start the minimum host-native infrastructure required for local development.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required for host-native PostgreSQL and Redis Stack." >&2
  exit 1
fi

if nc -z "$POSTGRES_HOST" "$POSTGRES_PORT" >/dev/null 2>&1; then
  echo "postgres already reachable at $POSTGRES_HOST:$POSTGRES_PORT"
else
  if ! brew list --versions postgresql@16 >/dev/null 2>&1; then
    echo "PostgreSQL is not reachable; installing PostgreSQL 16..."
    brew install postgresql@16
  fi
  brew services start postgresql@16
fi

if ! command -v redis-stack-server >/dev/null 2>&1; then
  echo "installing Redis Stack (includes RedisJSON)..."
  brew install --cask redis-stack-server
fi

bash "$ROOT/scripts/redis-stack-up.sh"
bash "$ROOT/scripts/qdrant-local-up.sh"

echo "host-native infrastructure is ready:"
echo "  postgres  $POSTGRES_HOST:$POSTGRES_PORT"
echo "  redis     127.0.0.1:${REDIS_PORT:-6380} (RedisJSON)"
echo "  qdrant    127.0.0.1:${QDRANT_PORT:-6333}"
