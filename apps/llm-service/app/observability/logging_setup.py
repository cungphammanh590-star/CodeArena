"""JSON structured logging for llm-service."""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
from typing import Any, Optional


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "@timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "service": "llm-service",
            "message": record.getMessage(),
        }
        for key in ("request_id", "trace_id", "tool_name", "session_id", "duration_ms"):
            val = getattr(record, key, None)
            if val is not None and val != "":
                payload[key] = val
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def setup_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    root.handlers.clear()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root.addHandler(handler)
    root.setLevel(getattr(logging, (level or "INFO").upper(), logging.INFO))

    file_handler = logging.FileHandler("/tmp/codearena-llm.log")
    file_handler.setFormatter(JsonFormatter())
    root.addHandler(file_handler)


def log_extra(
    *,
    request_id: str = "",
    tool_name: str = "",
    session_id: str = "",
    duration_ms: Optional[float] = None,
) -> dict[str, Any]:
    extra: dict[str, Any] = {}
    if request_id:
        extra["request_id"] = request_id
    if tool_name:
        extra["tool_name"] = tool_name
    if session_id:
        extra["session_id"] = session_id
    if duration_ms is not None:
        extra["duration_ms"] = round(duration_ms, 2)
    return extra
