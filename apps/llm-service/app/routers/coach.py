"""Coach dialogue API — LangGraph / SSE only.

Session prepare / hint / business ops live on business-service (Java).
See docs/architecture/BUSINESS_FLOW.md and COACH_TOOLS.md.
"""

from __future__ import annotations

import asyncio
import threading
from collections.abc import AsyncIterator, Iterator
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse

from app.coach.stream import chat_stream
from app.services.sse import sse_pack

router = APIRouter(tags=["coach-stream"])


def _json_error(message: str, *, status_code: int = 400) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"status": "error", "message": message},
    )


def _iter_sse(events: Iterator[dict[str, Any]]) -> Iterator[bytes]:
    for event in events:
        etype = str(event.get("type") or "message")
        yield sse_pack(etype, event)


async def _async_sse(
    events: Iterator[dict[str, Any]],
    cancel_event: threading.Event,
    request: Request,
) -> AsyncIterator[bytes]:
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue[bytes | None] = asyncio.Queue()

    def worker() -> None:
        try:
            for chunk in _iter_sse(events):
                if cancel_event.is_set():
                    break
                loop.call_soon_threadsafe(queue.put_nowait, chunk)
        finally:
            loop.call_soon_threadsafe(queue.put_nowait, None)

    threading.Thread(target=worker, daemon=True).start()
    while True:
        if await request.is_disconnected():
            cancel_event.set()
        item = await queue.get()
        if item is None:
            break
        yield item


@router.post("/api/coach/stream")
async def coach_stream(request: Request) -> Any:
    """唯一对外业务对话入口：多轮 SSE（LangGraph smart_agent）。"""
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001
        return _json_error("invalid JSON")

    session_id = str((payload or {}).get("session_id") or "").strip()
    message = str((payload or {}).get("message") or "").strip()
    action = str((payload or {}).get("action") or "").strip()
    raw_answers = (payload or {}).get("answers")
    answers: list[dict[str, Any]] = []
    if isinstance(raw_answers, list):
        answers = [a for a in raw_answers if isinstance(a, dict)]

    if not session_id:
        return _json_error("session_id required")
    if action == "submit_user_reply":
        if not answers:
            return _json_error("answers required for submit_user_reply")
    elif not message and not action:
        return _json_error("session_id and (message or action) required")

    user_public_id = (
        request.headers.get("X-User-Public-Id")
        or str((payload or {}).get("user_public_id") or "")
    ).strip()
    problem_id = (payload or {}).get("problem_id")
    try:
        pid = int(problem_id) if problem_id is not None and str(problem_id).strip() else 0
    except (TypeError, ValueError):
        pid = 0

    session: dict[str, Any] = {
        "session_id": session_id,
        "thread_id": session_id,
        "user_public_id": user_public_id,
        "problem_id": pid,
        "opening": str((payload or {}).get("opening") or ""),
    }
    cancel_event = threading.Event()
    events = chat_stream(
        session,
        message,
        action=action,
        answers=answers,
        cancel_event=cancel_event,
    )

    return StreamingResponse(
        _async_sse(events, cancel_event, request),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
