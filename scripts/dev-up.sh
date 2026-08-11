#!/usr/bin/env bash
# Boot CodeArena local infra (+ optional full stack).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-infra}"

if [[ ! -f .env && -f .env.example ]]; then
  echo "No .env found — copying .env.example -> .env"
  cp .env.example .env
fi

case "$MODE" in
  infra)
    echo "Starting infra (postgres, redis, nacos)..."
    docker compose -f docker-compose.infra.yml up -d
    ;;
  full|all)
    echo "Starting full default stack..."
    docker compose up -d --build
    ;;
  obs|observability)
    echo "Starting stack with observability profile..."
    docker compose --profile observability up -d --build
    ;;
  *)
    echo "Usage: $0 [infra|full|observability]"
    exit 1
    ;;
esac

echo "Done. Health tips:"
echo "  postgres  localhost:5432"
echo "  redis     localhost:6380 (redis-stack / RedisJSON)"
echo "  nacos     http://localhost:8848/nacos"
if [[ "$MODE" != "infra" ]]; then
  echo "  nginx     http://localhost/"
  echo "  gateway   http://localhost:8080"
  echo "  business  http://localhost:8090"
  echo "  llm       http://localhost:8091/health"
fi
