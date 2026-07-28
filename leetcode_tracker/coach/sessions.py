"""陪练会话元数据（与 LangGraph checkpoint 同库）。"""

from __future__ import annotations

import sqlite3
import uuid
from typing import Any, Optional

from leetcode_tracker.infra.timeutil import china_now_iso


def ensure_coach_session_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS coach_sessions (
            session_id TEXT PRIMARY KEY,
            submission_id TEXT NOT NULL,
            problem_id INTEGER NOT NULL,
            opening TEXT NOT NULL,
            context_markdown TEXT,
            submission_status TEXT,
            thread_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            abandoned_at TEXT
        )
        """
    )
    columns = {
        str(row[1])
        for row in conn.execute("PRAGMA table_info(coach_sessions)").fetchall()
    }
    if "submission_status" not in columns:
        conn.execute(
            "ALTER TABLE coach_sessions ADD COLUMN submission_status TEXT"
        )
    if "abandoned_at" not in columns:
        conn.execute(
            "ALTER TABLE coach_sessions ADD COLUMN abandoned_at TEXT"
        )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_coach_sessions_submission ON coach_sessions(submission_id)"
    )
    conn.commit()


def create_session(
    conn: sqlite3.Connection,
    *,
    submission_id: str,
    problem_id: int,
    opening: str,
    context_markdown: str,
    submission_status: str = "",
) -> dict[str, Any]:
    ensure_coach_session_schema(conn)
    session_id = str(uuid.uuid4())
    now = china_now_iso()
    conn.execute(
        """
        INSERT INTO coach_sessions (
            session_id, submission_id, problem_id, opening,
            context_markdown, submission_status, thread_id, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            session_id,
            submission_id,
            problem_id,
            opening,
            context_markdown,
            submission_status,
            session_id,
            now,
            now,
        ),
    )
    conn.commit()
    return {
        "session_id": session_id,
        "submission_id": submission_id,
        "problem_id": problem_id,
        "opening": opening,
        "context_markdown": context_markdown,
        "submission_status": submission_status,
        "thread_id": session_id,
        "created_at": now,
        "updated_at": now,
    }


def get_or_create_session(
    conn: sqlite3.Connection,
    *,
    submission_id: str,
    problem_id: int,
    opening: str,
    context_markdown: str,
    submission_status: str = "",
) -> tuple[dict[str, Any], bool]:
    """原子获取或创建提交级会话；返回 (session, created)。"""
    ensure_coach_session_schema(conn)
    try:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute(
            """
            SELECT * FROM coach_sessions
            WHERE submission_id = ?
              AND (abandoned_at IS NULL OR abandoned_at = '')
            ORDER BY created_at DESC
            LIMIT 1
            """,
            (submission_id,),
        ).fetchone()
        if row:
            # 复用时刷新 context / status / opening，避免升级后仍吃旧缓存
            now = china_now_iso()
            conn.execute(
                """
                UPDATE coach_sessions
                SET context_markdown = ?, submission_status = ?,
                    opening = ?, updated_at = ?
                WHERE session_id = ?
                """,
                (
                    context_markdown,
                    submission_status,
                    opening,
                    now,
                    row["session_id"],
                ),
            )
            conn.commit()
            reused = dict(row)
            reused["context_markdown"] = context_markdown
            reused["submission_status"] = submission_status
            reused["opening"] = opening
            reused["updated_at"] = now
            return reused, False

        session_id = str(uuid.uuid4())
        now = china_now_iso()
        conn.execute(
            """
            INSERT INTO coach_sessions (
                session_id, submission_id, problem_id, opening,
                context_markdown, submission_status, thread_id,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session_id,
                submission_id,
                problem_id,
                opening,
                context_markdown,
                submission_status,
                session_id,
                now,
                now,
            ),
        )
        conn.commit()
        return {
            "session_id": session_id,
            "submission_id": submission_id,
            "problem_id": problem_id,
            "opening": opening,
            "context_markdown": context_markdown,
            "submission_status": submission_status,
            "thread_id": session_id,
            "created_at": now,
            "updated_at": now,
        }, True
    except Exception:
        conn.rollback()
        raise


def get_session(conn: sqlite3.Connection, session_id: str) -> Optional[dict[str, Any]]:
    ensure_coach_session_schema(conn)
    row = conn.execute(
        "SELECT * FROM coach_sessions WHERE session_id = ?",
        (session_id,),
    ).fetchone()
    return dict(row) if row else None


def is_session_abandoned(session: dict[str, Any] | None) -> bool:
    if not session:
        return False
    return bool(str(session.get("abandoned_at") or "").strip())


def abandon_session(conn: sqlite3.Connection, session_id: str) -> None:
    """标记会话作废：后续 stream 拒绝，prepare 将新建会话。"""
    ensure_coach_session_schema(conn)
    now = china_now_iso()
    conn.execute(
        """
        UPDATE coach_sessions
        SET abandoned_at = ?, updated_at = ?
        WHERE session_id = ?
          AND (abandoned_at IS NULL OR abandoned_at = '')
        """,
        (now, now, session_id),
    )
    conn.commit()


def get_latest_session_for_submission(
    conn: sqlite3.Connection, submission_id: str
) -> Optional[dict[str, Any]]:
    ensure_coach_session_schema(conn)
    row = conn.execute(
        """
        SELECT * FROM coach_sessions
        WHERE submission_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (submission_id,),
    ).fetchone()
    return dict(row) if row else None


def get_latest_session_for_problem(
    conn: sqlite3.Connection, problem_id: int
) -> Optional[dict[str, Any]]:
    ensure_coach_session_schema(conn)
    row = conn.execute(
        """
        SELECT * FROM coach_sessions
        WHERE problem_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (problem_id,),
    ).fetchone()
    return dict(row) if row else None


def touch_session(conn: sqlite3.Connection, session_id: str) -> None:
    now = china_now_iso()
    conn.execute(
        "UPDATE coach_sessions SET updated_at = ? WHERE session_id = ?",
        (now, session_id),
    )
    conn.commit()


def rebind_session_submission(
    conn: sqlite3.Connection,
    session_id: str,
    *,
    submission_id: str,
    submission_status: str,
    context_markdown: str,
) -> dict[str, Any]:
    """将已有会话重绑到新提交；保留 session_id / thread_id / opening。"""
    ensure_coach_session_schema(conn)
    now = china_now_iso()
    conn.execute(
        """
        UPDATE coach_sessions
        SET submission_id = ?, submission_status = ?,
            context_markdown = ?, updated_at = ?
        WHERE session_id = ?
        """,
        (
            submission_id,
            submission_status,
            context_markdown,
            now,
            session_id,
        ),
    )
    conn.commit()
    session = get_session(conn, session_id)
    if session is None:
        raise ValueError(f"未找到会话: {session_id}")
    return session
