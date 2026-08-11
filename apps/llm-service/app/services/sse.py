"""SSE helpers for coach streaming."""

from __future__ import annotations

import json
from typing import Any


def sse_pack(event: str, data: dict[str, Any]) -> bytes:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")
