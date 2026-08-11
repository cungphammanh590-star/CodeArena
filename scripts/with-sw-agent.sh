#!/usr/bin/env bash
# Prefix JAVA_TOOL_OPTIONS with SkyWalking agent for local make targets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_JAR="$ROOT/deploy/observability/skywalking/agent/skywalking-agent.jar"
SERVICE_NAME="${SW_AGENT_NAME:-codearena-service}"
COLLECTOR="${SW_AGENT_COLLECTOR:-127.0.0.1:11800}"

if [[ ! -f "$AGENT_JAR" ]]; then
  echo "SkyWalking agent missing. Run: make obs-agents" >&2
  exit 1
fi

export SW_AGENT_NAME="$SERVICE_NAME"
export SW_AGENT_COLLECTOR="$COLLECTOR"
# agent.config.overlay keys; also pass via system properties supported by SW
export JAVA_TOOL_OPTIONS="-javaagent:${AGENT_JAR}=agent.service_name=${SERVICE_NAME},collector.backend_service=${COLLECTOR} ${JAVA_TOOL_OPTIONS:-}"
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
exec "$@"
