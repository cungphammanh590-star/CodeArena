"""已掌握屏蔽（不算进度）。"""

from __future__ import annotations

import sqlite3
from typing import Any

from leetcode_tracker.infra.timeutil import china_now_iso


def ensure_mastered_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS user_problem_flags (
            problem_id INTEGER PRIMARY KEY,
            mastered INTEGER NOT NULL DEFAULT 0,
            mastered_at TEXT,
            note TEXT
        )
        """
    )
    conn.commit()


def mastered_problem_ids(conn: sqlite3.Connection) -> set[int]:
    ensure_mastered_schema(conn)
    rows = conn.execute(
        "SELECT problem_id FROM user_problem_flags WHERE mastered = 1"
    ).fetchall()
    return {int(r["problem_id"]) for r in rows}


def is_mastered(conn: sqlite3.Connection, problem_id: int) -> bool:
    ensure_mastered_schema(conn)
    row = conn.execute(
        "SELECT mastered FROM user_problem_flags WHERE problem_id = ?",
        (int(problem_id),),
    ).fetchone()
    return bool(row and int(row["mastered"] or 0) == 1)


def count_mastered_in_set(conn: sqlite3.Connection, ids: set[int]) -> int:
    if not ids:
        return 0
    mastered = mastered_problem_ids(conn)
    return len(ids & mastered)


def set_mastered(
    conn: sqlite3.Connection,
    problem_id: int,
    *,
    mastered: bool = True,
    note: str = "",
) -> dict[str, Any]:
    ensure_mastered_schema(conn)
    pid = int(problem_id)
    if pid <= 0:
        raise ValueError("无效 problem_id")
    if mastered:
        conn.execute(
            """
            INSERT INTO user_problem_flags (problem_id, mastered, mastered_at, note)
            VALUES (?, 1, ?, ?)
            ON CONFLICT(problem_id) DO UPDATE SET
                mastered = 1,
                mastered_at = excluded.mastered_at,
                note = excluded.note
            """,
            (pid, china_now_iso(), note or None),
        )
    else:
        conn.execute(
            """
            INSERT INTO user_problem_flags (problem_id, mastered, mastered_at, note)
            VALUES (?, 0, NULL, NULL)
            ON CONFLICT(problem_id) DO UPDATE SET
                mastered = 0,
                mastered_at = NULL
            """,
            (pid,),
        )
    conn.commit()
    return {"problem_id": pid, "mastered": mastered}


def list_mastered(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    ensure_mastered_schema(conn)
    rows = conn.execute(
        """
        SELECT f.problem_id, f.mastered_at, f.note,
               COALESCE(p.title, ps.title, '') AS title,
               COALESCE(p.slug, ps.title_slug, '') AS slug,
               COALESCE(p.difficulty, ps.difficulty, '') AS difficulty
        FROM user_problem_flags f
        LEFT JOIN problems p ON p.problem_id = f.problem_id
        LEFT JOIN problem_stats ps ON ps.problem_id = f.problem_id
        WHERE f.mastered = 1
        ORDER BY f.mastered_at DESC, f.problem_id
        """
    ).fetchall()
    return [
        {
            "problem_id": int(r["problem_id"]),
            "title": str(r["title"] or ""),
            "slug": str(r["slug"] or ""),
            "difficulty": str(r["difficulty"] or ""),
            "mastered_at": r["mastered_at"],
            "note": r["note"],
            "url": (
                f"https://leetcode.cn/problems/{r['slug']}/"
                if r["slug"]
                else f"/problems/{r['problem_id']}"
            ),
        }
        for r in rows
    ]


def filter_expand(
    candidates: list[dict[str, Any]],
    mastered: set[int],
    *,
    limit: int,
    cap: int = 50,
    id_key: str = "id",
) -> list[dict[str, Any]]:
    """从候选中丢掉 mastered；若不足则假定 candidates 已按优先级排好且足够长。"""
    out: list[dict[str, Any]] = []
    for c in candidates[:cap]:
        pid = int(c.get(id_key) or c.get("problem_id") or 0)
        if pid <= 0 or pid in mastered:
            continue
        out.append(c)
        if len(out) >= limit:
            break
    return out
