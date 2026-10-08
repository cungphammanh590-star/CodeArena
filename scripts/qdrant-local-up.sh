#!/usr/bin/env bash
# Download and start a repository-local Qdrant binary without Docker.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

VERSION="${QDRANT_VERSION:-1.13.2}"
PORT="${QDRANT_PORT:-6333}"
RUNTIME="$ROOT/.local-infra/qdrant"
BIN="$RUNTIME/qdrant"
PID_FILE="$RUNTIME/qdrant.pid"
LOG_FILE="$RUNTIME/qdrant.log"

if curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then
  echo "qdrant already running on :$PORT"
  exit 0
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) TARGET="aarch64-apple-darwin" ;;
  Darwin-x86_64) TARGET="x86_64-apple-darwin" ;;
  Linux-x86_64) TARGET="x86_64-unknown-linux-musl" ;;
  Linux-aarch64|Linux-arm64) TARGET="aarch64-unknown-linux-musl" ;;
  *) echo "unsupported platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

mkdir -p "$RUNTIME/storage" "$RUNTIME/snapshots"
if [[ ! -x "$BIN" ]]; then
  ARCHIVE="$RUNTIME/qdrant.tar.gz"
  URL="https://github.com/qdrant/qdrant/releases/download/v${VERSION}/qdrant-${TARGET}.tar.gz"
  echo "downloading qdrant v$VERSION for $TARGET..."
  curl -fsSL --retry 3 --continue-at - "$URL" -o "$ARCHIVE"
  tar -xzf "$ARCHIVE" -C "$RUNTIME" qdrant
  chmod +x "$BIN"
  rm -f "$ARCHIVE"
fi

if [[ -f "$PID_FILE" ]] && kill -0 "$(<"$PID_FILE")" 2>/dev/null; then
  echo "qdrant process already running (pid $(<"$PID_FILE")) but :$PORT is not ready" >&2
  exit 1
fi

echo "starting qdrant on :$PORT..."
(
  cd "$RUNTIME"
  QDRANT__SERVICE__HTTP_PORT="$PORT" \
  QDRANT__SERVICE__GRPC_PORT="${QDRANT_GRPC_PORT:-6334}" \
  QDRANT__STORAGE__STORAGE_PATH="$RUNTIME/storage" \
  QDRANT__STORAGE__SNAPSHOTS_PATH="$RUNTIME/snapshots" \
  nohup "$BIN" >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
)

for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then
    echo "qdrant ready on :$PORT (log: $LOG_FILE)"
    exit 0
  fi
  sleep 1
done

echo "qdrant failed to become ready; see $LOG_FILE" >&2
exit 1
