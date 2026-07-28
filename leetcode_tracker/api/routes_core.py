"""采集热路径与健康检查：/submit、/health。"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from leetcode_tracker.coach.deps import coach_dependencies_available
from leetcode_tracker.infra.config import load_config
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.kg.import_maps import kg_is_imported
from leetcode_tracker.core.store import StoreError, count_submissions, save_submission

router = APIRouter()


@router.get("/health")
def health() -> Any:
    cfg = load_config()
    port = int(cfg["port"])
    host = str(cfg["host"])
    try:
        conn = init_db()
        try:
            from leetcode_tracker.kg.import_maps import ensure_kg_imported

            try:
                ensure_kg_imported(conn)
            except Exception:  # noqa: BLE001
                pass
            count = count_submissions(conn)
            kg_imported = kg_is_imported(conn)
        finally:
            conn.close()
        return {
            "status": "ok",
            "server": "fastapi",
            "db_connected": True,
            "submissions_count": count,
            "port": port,
            "host": host,
            "kg_imported": kg_imported,
            "coach_available": coach_dependencies_available(),
            "llm_provider": str((cfg.get("llm") or {}).get("provider") or "ollama"),
            "llm_api_provider": str((cfg.get("llm") or {}).get("api_provider") or ""),
        }
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500,
            content={
                "status": "error",
                "server": "fastapi",
                "db_connected": False,
                "port": port,
                "host": host,
                "kg_imported": False,
                "coach_available": coach_dependencies_available(),
                "message": str(exc),
            },
        )


@router.post("/submit")
async def submit(request: Request) -> JSONResponse:
    """采集热路径：只入库。禁止调用 prepare / LLM / SSE。"""
    try:
        payload = await request.json()
        if not isinstance(payload, dict):
            return JSONResponse(
                status_code=400,
                content={"status": "error", "message": "JSON body must be an object"},
            )
    except Exception:  # noqa: BLE001
        return JSONResponse(
            status_code=400,
            content={"status": "error", "message": "invalid JSON"},
        )

    try:
        conn = init_db()
        try:
            result = save_submission(conn, payload)
        finally:
            conn.close()
        if result.created:
            return JSONResponse(
                {
                    "status": "success",
                    "message": "Submission saved",
                    "submission_id": result.submission_id,
                    "created": True,
                }
            )
        return JSONResponse(
            {
                "status": "success",
                "message": "Submission already exists",
                "submission_id": result.submission_id,
                "created": False,
            }
        )
    except StoreError as exc:
        return JSONResponse(
            status_code=exc.status_code,
            content={"status": "error", "message": exc.message},
        )
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500,
            content={"status": "error", "message": str(exc)},
        )
