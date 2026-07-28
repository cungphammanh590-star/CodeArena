"""陪练会话：按库内最新提交惰性重绑（session_id 不变）。"""

from __future__ import annotations

import sqlite3
from typing import Any, Optional

from leetcode_tracker.coach.context import build_coach_context
from leetcode_tracker.coach.sessions import rebind_session_submission
from leetcode_tracker.core.submissions import get_latest_submission_for_problem

# 口头「改了/已提交」但库无更新的提交：软提示，不阻断进图
NO_NEWER_SUBMISSION_INFO = (
    "库里还没有查到比当前更新的提交。"
    "请确认是否已在力扣提交最新代码；若刚提交请稍等扩展同步后再发。"
    "下面仍按库内已有代码继续分析。"
)

CLAIM_CHANGED_PHRASES = (
    "我改了",
    "我已经改",
    "已经改了",
    "已经修改",
    "已修改",
    "我已经修改",
    "这个也改了",
    "这个也修改了",
    "这个也加入了",
    "已提交",
    "我提交了",
    "重新提交",
    "我又提交",
    "提交了新",
)

PROGRESS_FEEDBACK_PHRASES = (
    "我改了",
    "已经改",
    "已修改",
    "我已经修改",
    "这个也改",
    "做错了",
    "为什么做错",
    "我为什么做错",
)


def claims_code_updated(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    return any(p in t for p in CLAIM_CHANGED_PHRASES)


def is_progress_feedback(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    return any(p in t for p in PROGRESS_FEEDBACK_PHRASES)


def maybe_sync_session_submission(
    conn: sqlite3.Connection, session: dict[str, Any]
) -> tuple[dict[str, Any], Optional[dict[str, Any]]]:
    """同 session 下若该题最新 submission 变了则静默重绑，返回 sync_meta。"""
    sid = str(session.get("submission_id") or "")
    problem_id = int(session.get("problem_id") or 0)
    if sid.startswith("mode:") or problem_id <= 0:
        return session, None

    latest = get_latest_submission_for_problem(conn, problem_id)
    if latest is None:
        return session, None

    latest_id = str(latest["submission_id"])
    if latest_id == sid:
        return session, None

    old_status = str(session.get("submission_status") or "")
    ctx = build_coach_context(conn, latest_id)
    updated = rebind_session_submission(
        conn,
        str(session["session_id"]),
        submission_id=latest_id,
        submission_status=str(ctx["status"]),
        context_markdown=str(ctx["markdown"]),
    )
    new_status = str(ctx["status"])
    meta = {
        "from_status": old_status,
        "to_status": new_status,
        "submission_id": latest_id,
        "code_changed": True,
        "cleared_compile_rejects": (
            old_status == "Compile Error" and new_status != "Compile Error"
        ),
    }
    return updated, meta
