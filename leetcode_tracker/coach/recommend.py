"""共享推荐：Hot100 新题 only（不含复习）。"""

from __future__ import annotations

import sqlite3
from typing import Any, Optional

from leetcode_tracker.coach.hot100 import (
    accepted_problem_ids,
    hot100_progress,
    load_hot100,
)
from leetcode_tracker.infra.timeutil import china_now_iso


def ensure_recommendation_log_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS coach_recommendation_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            problem_id INTEGER NOT NULL,
            list_id TEXT NOT NULL DEFAULT 'hot100',
            recommended_at TEXT NOT NULL,
            strategy TEXT
        )
        """
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_coach_rec_log_at "
        "ON coach_recommendation_log(recommended_at)"
    )
    conn.commit()


def recent_recommended_ids(
    conn: sqlite3.Connection, *, days: int = 7
) -> set[int]:
    ensure_recommendation_log_schema(conn)
    rows = conn.execute(
        """
        SELECT problem_id, recommended_at FROM coach_recommendation_log
        ORDER BY recommended_at DESC LIMIT 200
        """
    ).fetchall()
    from datetime import datetime

    from leetcode_tracker.infra.timeutil import china_now

    now = china_now().replace(tzinfo=None)
    out: set[int] = set()
    for r in rows:
        try:
            ts = datetime.fromisoformat(str(r["recommended_at"])[:19])
        except ValueError:
            continue
        if (now - ts).days <= days:
            out.add(int(r["problem_id"]))
    return out


def log_recommendations(
    conn: sqlite3.Connection,
    candidates: list[dict[str, Any]],
    *,
    list_id: str = "hot100",
) -> None:
    ensure_recommendation_log_schema(conn)
    now = china_now_iso()
    for c in candidates:
        pid = int(c.get("id") or c.get("problem_id") or 0)
        if pid <= 0:
            continue
        conn.execute(
            """
            INSERT INTO coach_recommendation_log
            (problem_id, list_id, recommended_at, strategy)
            VALUES (?, ?, ?, ?)
            """,
            (pid, list_id, now, str(c.get("strategy") or "")),
        )
    conn.commit()


def _as_candidate(
    p: dict[str, Any],
    *,
    strategy: str,
    reason: str,
) -> dict[str, Any]:
    return {
        "problem_id": int(p["id"]),
        "id": int(p["id"]),
        "title": str(p.get("title") or ""),
        "difficulty": str(p.get("difficulty") or ""),
        "slug": str(p.get("slug") or ""),
        "tags": list(p.get("tags") or []),
        "order": int(p.get("order") or 0),
        "matched_tags": [],
        "strategy": strategy,
        "kind": "new",
        "reason": reason,
        "url": f"https://leetcode.cn/problems/{p.get('slug')}/"
        if p.get("slug")
        else f"/problems/{p['id']}",
    }


def recommend_problems(
    conn: sqlite3.Connection,
    *,
    weak_tags: list[str],
    limit: int = 3,
    prefer_difficulty: str = "Medium",  # noqa: ARG001
    current_tags: Optional[list[str]] = None,
) -> list[dict[str, Any]]:
    """只推荐 Hot100 未 AC 新题。级联：续刷 → 同标签巩固 → 薄弱 → 补位。"""
    catalog = load_hot100()
    if not catalog:
        return []

    solved = accepted_problem_ids(conn)
    recent = recent_recommended_ids(conn, days=7)
    progress = hot100_progress(conn)
    unsolved = [p for p in catalog if p["id"] not in solved]

    if not unsolved and progress["ratio"] >= 0.9:
        return [
            {
                "problem_id": 0,
                "id": 0,
                "title": "",
                "difficulty": "",
                "slug": "",
                "tags": [],
                "order": 0,
                "matched_tags": [],
                "strategy": "completed",
                "kind": "message",
                "reason": (
                    f"Hot100 新题已全部 AC（{progress['done']}/{progress['total']}）。"
                    "去做「今日复习」温习旧题，或按知识图谱专题深挖。"
                ),
                "url": "",
            }
        ]

    picked: list[dict[str, Any]] = []
    picked_ids: set[int] = set()

    def _take(c: dict[str, Any]) -> None:
        pid = int(c["id"])
        if pid in picked_ids or pid == 0:
            return
        if len(picked) >= limit:
            return
        picked.append(c)
        picked_ids.add(pid)

    if unsolved:
        nxt = unsolved[0]
        _take(
            _as_candidate(
                nxt,
                strategy="list_continue",
                reason=(
                    f"Hot100 续刷：进度 {progress['done']}/{progress['total']}，"
                    f"下一道（order {nxt['order']}）"
                ),
            )
        )

    focus_tags = [t for t in (current_tags or []) if t] or list(weak_tags or [])[:2]
    for p in unsolved:
        if p["id"] in picked_ids or p["id"] in recent:
            continue
        overlap = [t for t in p["tags"] if t in focus_tags]
        if not overlap:
            continue
        c = _as_candidate(
            p,
            strategy="same_tag",
            reason=f"同标签新题：{', '.join(overlap)}",
        )
        c["matched_tags"] = overlap
        _take(c)
        break

    weak = [t for t in (weak_tags or []) if t]
    if weak and len(picked) < limit:
        top = weak[0]
        for p in unsolved:
            if p["id"] in picked_ids or p["id"] in recent:
                continue
            if top not in p["tags"]:
                continue
            c = _as_candidate(
                p,
                strategy="weak_tag",
                reason=f"薄弱补强新题：{top}",
            )
            c["matched_tags"] = [top]
            _take(c)
            break

    for p in unsolved:
        if len(picked) >= limit:
            break
        if p["id"] in picked_ids or p["id"] in recent:
            continue
        _take(
            _as_candidate(
                p,
                strategy="fill",
                reason="Hot100 未完成补位",
            )
        )

    if not picked:
        for p in unsolved[:limit]:
            _take(
                _as_candidate(
                    p,
                    strategy="list_continue",
                    reason="Hot100 未完成",
                )
            )

    if picked and not any(c.get("kind") == "message" for c in picked):
        try:
            log_recommendations(conn, picked)
        except Exception:  # noqa: BLE001
            pass

    return picked[:limit]


def format_recommendations_fallback(candidates: list[dict[str, Any]]) -> str:
    if not candidates:
        return (
            "暂时没有可推荐的 Hot100 新题。"
            "若清单已全部 AC，请用「今日复习」温习旧题。"
        )
    if len(candidates) == 1 and candidates[0].get("kind") == "message":
        return str(candidates[0].get("reason") or "")

    lines = ["Hot100 新题推荐（不含复习）："]
    for i, c in enumerate(candidates, 1):
        pid = c.get("problem_id") or c.get("id")
        title = c.get("title") or ""
        diff = c.get("difficulty") or ""
        reason = c.get("reason") or ""
        url = c.get("url") or f"/problems/{pid}"
        lines.append(f"{i}. {pid}. {title}（{diff}）— {reason}")
        lines.append(f"   链接：{url}")
    return "\n".join(lines)


def polish_prompt(candidates: list[dict[str, Any]], profile_summary: str) -> str:
    listing = format_recommendations_fallback(candidates)
    return (
        "用一两句口语把下列【新题】推荐说给刷题用户听，不要改题号，"
        "不要增加清单外的题，不要混入复习旧题。不要输出代码。\n\n"
        f"画像摘要：{profile_summary}\n\n"
        f"候选：\n{listing}\n"
    )
