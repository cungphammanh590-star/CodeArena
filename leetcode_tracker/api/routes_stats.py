"""统计与题目 API。"""

from __future__ import annotations

from datetime import date
from typing import Any, Optional

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

from leetcode_tracker.coach.mastered import is_mastered
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.core.problem_stats import (
    ensure_stats_materialized,
    get_daily_stats_rows,
    get_llm_context,
    get_problem_stats_row,
    get_problem_submissions,
    list_problem_stats,
)
from leetcode_tracker.core.stats import get_overview, overview_to_dict

router = APIRouter()


@router.get("/api/stats")
def api_stats(date_q: Optional[str] = Query(None, alias="date")) -> Any:
    day: Optional[date] = None
    if date_q is not None and date_q.strip():
        try:
            day = date.fromisoformat(date_q.strip())
        except ValueError:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "error",
                    "message": "无效日期，请使用 YYYY-MM-DD",
                },
            )
    try:
        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            stats = get_overview(conn, day=day)
        finally:
            conn.close()
        return overview_to_dict(stats)
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


@router.get("/api/problems")
def api_problems() -> Any:
    try:
        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            items = list_problem_stats(conn)
        finally:
            conn.close()
        return {"problems": items}
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


@router.get("/api/problems/{problem_id}/stats")
def api_problem_stats(problem_id: int) -> Any:
    try:
        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            row = get_problem_stats_row(conn, problem_id)
            if row is None:
                return JSONResponse(
                    status_code=404, content={"status": "error", "message": "not found"}
                )
            row["mastered"] = is_mastered(conn, problem_id)
            daily = get_daily_stats_rows(conn, problem_id, limit=90)
            submissions = get_problem_submissions(conn, problem_id, limit=80)
            return {"problem": row, "daily": daily, "submissions": submissions}
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )


@router.get("/api/problems/{problem_id}/llm-context")
def api_llm_context(problem_id: int) -> Any:
    try:
        conn = init_db()
        try:
            ensure_stats_materialized(conn)
            text = get_llm_context(conn, problem_id)
            return {"problem_id": problem_id, "markdown": text}
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001
        return JSONResponse(
            status_code=500, content={"status": "error", "message": str(exc)}
        )
