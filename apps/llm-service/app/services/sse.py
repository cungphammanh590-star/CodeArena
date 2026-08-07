"""SSE helpers for coach streaming."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any


def sse_pack(event: str, data: dict[str, Any]) -> bytes:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")


async def mock_token_stream(
    *,
    session_id: str,
    message: str,
    action: str = "",
    delay_seconds: float = 0.04,
) -> AsyncIterator[bytes]:
    """Yield mock SSE token / done events for local development."""
    prompt = message or action or "(empty)"
    text = (
        f"[CodeArena mock coach] session={session_id} "
        f"reply to: {prompt[:160]}"
    )
    yield sse_pack(
        "meta",
        {"type": "meta", "session_id": session_id, "mock": True},
    )
    for ch in text:
        yield sse_pack("token", {"type": "token", "delta": ch})
        if delay_seconds > 0:
            await asyncio.sleep(delay_seconds)
    yield sse_pack(
        "done",
        {
            "type": "done",
            "session_id": session_id,
            "content": text,
            "mock": True,
        },
    )
