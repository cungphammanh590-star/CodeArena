#!/usr/bin/env bash
# Download SkyWalking Java agent 9.7.x into deploy/observability/skywalking/agent
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DEST="$ROOT/deploy/observability/skywalking"
VERSION="${SW_AGENT_VERSION:-9.7.0}"
ARCHIVE="apache-skywalking-java-agent-${VERSION}.tgz"
URL="https://archive.apache.org/dist/skywalking/java-agent/${VERSION}/${ARCHIVE}"

mkdir -p "$DEST"
cd "$DEST"

if [[ -f agent/skywalking-agent.jar ]]; then
  echo "SkyWalking Java agent already present: $DEST/agent"
  exit 0
fi

echo "Downloading $URL ..."
curl -fsSL -o "$ARCHIVE" "$URL"
tar -xzf "$ARCHIVE"
# tarball usually extracts to skywalking-agent/
if [[ -d skywalking-agent ]]; then
  rm -rf agent
  mv skywalking-agent agent
fi
rm -f "$ARCHIVE"

if [[ -f "$DEST/agent.config.overlay" ]]; then
  cp "$DEST/agent.config.overlay" "$DEST/agent/config/agent.config"
fi

echo "Installed SkyWalking Java agent at $DEST/agent"
echo "Use: export JAVA_TOOL_OPTIONS=\"-javaagent:$DEST/agent/skywalking-agent.jar\""
