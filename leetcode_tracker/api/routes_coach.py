"""陪练 API：prepare / stream / chat / session / hint（与 /submit 解耦）。"""

from __future__ import annotations

import asyncio
import json
import threading
from typing import Any, AsyncIterator, Optional

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse

from leetcode_tracker.coach.deps import (
    coach_dependencies_available,
    coach_import_error_message,
)
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.kg.import_maps import kg_is_imported
from leetcode_tracker.core.problem_stats import ensure_stats_materialized
from leetcode_tracker.core.submissions import get_problem_id_by_slug

router = APIRouter()


def _coach_unavailable() -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content={"status": "error", "message": coach_import_error_message()},
    )


def _resolve_problem_id(
    problem_id: Optional[int],
    slug: Optional[str],
    path_id: Optional[int] = None,
) -> Optional[int]:
    if path_id is not None:
        return path_id
    if problem_id is not None:
        return problem_id
    if slug:
        conn = init_db()
        try:
            return get_problem_id_by_slug(conn, slug.strip())
        finally:
            conn.close()
    return None


@router.get("/api/coach/hint")
@router.get("/api/coach/hint/{path_problem_id}")
def coach_hint(
    path_problem_id: Optional[int] = None,
    problem_id: Optional[int] = Query(default=None),
    slug: Optional[str] = Query(default=None),
) -> Any:
    try:
        pid = _resolve_problem_id(problem_id, slug, path_problem_id)
        if pid is None:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "error",
                    "message": "需要 problem_id 或已在库中的 slug；先在题目页提交一次可自动同步题号",
                },
            )
        from leetcode_tracker.coach.hint import build_problem_hint

        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            hint = build_problem_hint(conn, pid)
        finally:
            conn.close()
        return {"status": "ok", **hint}
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


@router.get("/api/coach/session")
def coach_session(
    submission_id: Optional[str] = Query(default=None),
    submission: Optional[str] = Query(default=None),
    problem_id: Optional[int] = Query(default=None),
) -> Any:
    sid = (submission_id or submission or "").strip()
    if not sid and problem_id is None:
        return JSONResponse(
            status_code=400,
            content={
                "status": "error",
                "message": "submission_id or problem_id required",
            },
        )
    try:
        from leetcode_tracker.coach.sessions import (
            get_latest_session_for_problem,
            get_latest_session_for_submission,
        )

        conn = init_db()
        try:
            session = (
                get_latest_session_for_submission(conn, sid)
                if sid
                else get_latest_session_for_problem(conn, int(problem_id))
            )
        finally:
            conn.close()
        if session is None:
            return JSONResponse(
                status_code=404,
                content={"status": "error", "message": "session not found"},
            )
        return {
            "status": "ok",
            "session_id": session["session_id"],
            "opening": session["opening"],
            "problem_id": session["problem_id"],
            "submission_id": session["submission_id"],
        }
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


async def _prepare_body(request: Request) -> Any:
    if not coach_dependencies_available():
        return _coach_unavailable()
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001
        return JSONResponse(
            status_code=400, content={"status": "error", "message": "invalid JSON"}
        )
    submission_id = str((payload or {}).get("submission_id") or "").strip()
    mode = str((payload or {}).get("mode") or "").strip()
    raw_problem_id = (payload or {}).get("problem_id")
    try:
        problem_id = int(raw_problem_id) if raw_problem_id not in (None, "") else None
    except (TypeError, ValueError):
        return JSONResponse(
            status_code=400,
            content={"status": "error", "message": "problem_id must be an integer"},
        )
    if mode in {"daily_review", "recommend", "review"}:
        pass
    elif not submission_id and problem_id is None:
        return JSONResponse(
            status_code=400,
            content={
                "status": "error",
                "message": "submission_id or problem_id or mode required",
            },
        )

    def _run() -> dict[str, Any]:
        from leetcode_tracker.coach import service as coach_service

        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            if mode not in {"daily_review", "recommend", "review"} and not kg_is_imported(
                conn
            ):
                raise RuntimeError("__kg_missing__")
            return coach_service.prepare(
                conn,
                submission_id,
                problem_id=problem_id,
                mode=mode,
            )
        finally:
            conn.close()

    try:
        result = await asyncio.to_thread(_run)
        return {"status": "ok", **result}
    except RuntimeError as exc:
        if str(exc) == "__kg_missing__":
            return JSONResponse(
                status_code=409,
                content={
                    "status": "error",
                    "message": "知识图谱未导入，请先运行: leetcode-tracker kg import",
                },
            )
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )
    except ValueError as exc:
        return JSONResponse(
            status_code=404, content={"status": "error", "message": str(exc)}
        )
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


@router.post("/api/coach/prepare")
async def coach_prepare(request: Request) -> Any:
    return await _prepare_body(request)


@router.post("/api/coach/engage")
async def coach_engage(request: Request) -> Any:
    """别名：与 prepare 相同。"""
    return await _prepare_body(request)


@router.post("/api/coach/chat")
async def coach_chat(request: Request) -> Any:
    if not coach_dependencies_available():
        return _coach_unavailable()
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001
        return JSONResponse(
            status_code=400, content={"status": "error", "message": "invalid JSON"}
        )
    session_id = str((payload or {}).get("session_id") or "").strip()
    message = str((payload or {}).get("message") or "").strip()
    if not session_id or not message:
        return JSONResponse(
            status_code=400,
            content={"status": "error", "message": "session_id and message required"},
        )
    try:
        from leetcode_tracker.coach import service as coach_service

        def _run_chat() -> dict[str, Any]:
            conn = init_db()
            try:
                return coach_service.chat(conn, session_id, message)
            finally:
                conn.close()

        result = await asyncio.to_thread(_run_chat)
        return {"status": "ok", **result}
    except ValueError as exc:
        return JSONResponse(
            status_code=404, content={"status": "error", "message": str(exc)}
        )
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


def _sse_pack(event: str, data: dict[str, Any]) -> bytes:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")


@router.post("/api/coach/stream")
async def coach_stream(request: Request) -> Any:
    if not coach_dependencies_available():
        return _coach_unavailable()
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001
        return JSONResponse(
            status_code=400, content={"status": "error", "message": "invalid JSON"}
        )
    session_id = str((payload or {}).get("session_id") or "").strip()
    message = str((payload or {}).get("message") or "").strip()
    action = str((payload or {}).get("action") or "").strip()
    if not session_id or (not message and not action):
        return JSONResponse(
            status_code=400,
            content={
                "status": "error",
                "message": "session_id and (message or action) required",
            },
        )

    from leetcode_tracker.coach import service as coach_service

    check_conn = init_db()
    try:
        from leetcode_tracker.coach.sessions import get_session, is_session_abandoned

        session_row = get_session(check_conn, session_id)
        if session_row is None:
            return JSONResponse(
                status_code=404,
                content={"status": "error", "message": "session not found"},
            )
        if is_session_abandoned(session_row):
            return JSONResponse(
                status_code=409,
                content={
                    "status": "error",
                    "code": "session_abandoned",
                    "message": "本对话已结束，请重新打开陪练再继续。",
                    "reopen_required": True,
                },
            )
    finally:
        check_conn.close()
    if not coach_service.try_acquire_session(session_id):
        return JSONResponse(
            status_code=409,
            content={
                "status": "error",
                "code": "session_busy",
                "message": "该会话正在处理上一条消息",
            },
        )

    cancel_event = threading.Event()

    async def event_gen() -> AsyncIterator[bytes]:
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[Optional[dict[str, Any]]] = asyncio.Queue()

        def produce() -> None:
            # 连接必须在工作线程内创建（sqlite 非跨线程安全）
            conn = init_db()
            try:
                for event in coach_service.chat_stream(
                    conn,
                    session_id,
                    message,
                    action=action,
                    cancel_event=cancel_event,
                    lock_acquired=True,
                ):
                    loop.call_soon_threadsafe(queue.put_nowait, event)
            except Exception as exc:  # noqa: BLE001
                loop.call_soon_threadsafe(
                    queue.put_nowait, {"type": "error", "message": str(exc)}
                )
            finally:
                conn.close()
                coach_service.release_session(session_id)
                loop.call_soon_threadsafe(queue.put_nowait, None)

        worker = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=0.5)
                except asyncio.TimeoutError:
                    continue
                if event is None:
                    break
                etype = str(event.get("type") or "message")
                yield _sse_pack(etype, event)
                if etype == "error":
                    break
        finally:
            cancel_event.set()
            if not worker.done():
                worker.cancel()

    return StreamingResponse(
        event_gen(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
