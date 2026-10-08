"""Small, privacy-aware Agent trace events for Loki/Grafana.

These events expose graph decisions and external actions, not hidden model
chain-of-thought. Payloads are bounded and sensitive/code fields are redacted.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from app.observability.logging_setup import log_extra
from app.observability.request_context import get_request_id

logger = logging.getLogger("agent.trace")

_REDACT_KEYS = {
    "api_key", "authorization", "code", "content", "history", "password",
    "secret", "snippet", "token",
}


def _safe(value: Any, *, key: str = "", depth: int = 0) -> Any:
    if any(part in key.lower() for part in _REDACT_KEYS):
        return "<redacted>"
    if depth >= 3:
        return "<omitted>"
    if isinstance(value, dict):
        return {str(k): _safe(v, key=str(k), depth=depth + 1) for k, v in list(value.items())[:20]}
    if isinstance(value, (list, tuple)):
        return [_safe(v, depth=depth + 1) for v in list(value)[:20]]
    if isinstance(value, str):
        return value if len(value) <= 500 else value[:497] + "..."
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return str(value)[:500]


def result_summary(raw: Any) -> dict[str, Any]:
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
    except (json.JSONDecodeError, TypeError):
        value = {"text_length": len(str(raw or ""))}
    if not isinstance(value, dict):
        return {"result_type": type(value).__name__, "items": len(value) if isinstance(value, list) else None}
    preferred = (
        "ok", "error", "count", "total", "problem_id", "matched", "ambiguous",
        "unmatched", "documents", "knowledge_points", "items", "candidates",
    )
    summary = {key: value[key] for key in preferred if key in value}
    if not summary:
        summary = {"result_keys": list(value)[:20]}
    return _safe(summary)


def result_status(raw: Any) -> str:
    """Map common tool result envelopes to a low-cardinality trace status."""
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
    except (json.JSONDecodeError, TypeError):
        return "ok"
    if isinstance(value, dict):
        if value.get("ok") is False or value.get("error"):
            return "error"
    return "ok"


def agent_event(
    event_type: str,
    *,
    session_id: str,
    node: str = "",
    tool_name: str = "",
    status: str = "",
    duration_ms: float | None = None,
    data: dict[str, Any] | None = None,
) -> None:
    logger.info(
        "agent_event %s",
        event_type,
        extra=log_extra(
            request_id=get_request_id(),
            session_id=session_id,
            tool_name=tool_name,
            duration_ms=duration_ms,
            event_type=event_type,
            node=node,
            status=status,
            event_data=_safe(data or {}),
        ),
    )
