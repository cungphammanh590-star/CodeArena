"""固定间隔复习 due（MVP，非 FSRS）。只出已 AC 旧题。"""

from __future__ import annotations

import sqlite3
from datetime import datetime
from typing import Any

from leetcode_tracker.coach.catalog import catalog_by_id, accepted_problem_ids
from leetcode_tracker.coach.mastered import filter_expand, mastered_problem_ids
from leetcode_tracker.infra.config import get_learning_config
from leetcode_tracker.infra.timeutil import china_now

# 参考 leetcode-review-planner：简化阶梯（天）
REVIEW_INTERVALS_DAYS = (1, 3, 7, 14, 30)
# 无 stage 表时：距最近提交超过该天数则进入 due
DEFAULT_DUE_AFTER_DAYS = 7


def _parse_ts(raw: str | None) -> datetime | None:
    if not raw:
        return None
    text = str(raw).strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(text[:19])
    except ValueError:
        return None


def _human_review_reason(age_days: int) -> str:
    if age_days < 14:
        return "差不多一周没碰了，温习一下"
    if age_days < 28:
        return "半个月没碰了，回顾一下"
    if age_days < 45:
        return "快一个月没看了，建议过一遍"
    return "已经很久没回顾了"


def list_review_due(
    conn: sqlite3.Connection,
    *,
    due_after_days: int = DEFAULT_DUE_AFTER_DAYS,
    limit: int = 50,
) -> list[dict[str, Any]]:
    """活跃题单 ∩ 已 AC ∩ 距 last 超过阈值 ∩ ¬mastered。"""
    learning = get_learning_config()
    if not learning.get("list_mode", True):
        return []

    catalog = catalog_by_id(conn)
    solved = accepted_problem_ids(conn)
    mastered = mastered_problem_ids(conn)
    now = china_now().replace(tzinfo=None)
    due: list[dict[str, Any]] = []
    for pid in solved:
        if pid in mastered:
            continue
        meta = catalog.get(pid)
        if meta is None:
            continue
        row = conn.execute(
            """
            SELECT first_accepted_at, last_submitted_at, struggle_score, accepted_count
            FROM problem_stats WHERE problem_id = ?
            """,
            (pid,),
        ).fetchone()
        if row is None:
            continue
        anchor = _parse_ts(row["last_submitted_at"]) or _parse_ts(
            row["first_accepted_at"]
        )
        if anchor is None:
            continue
        age_days = (now - anchor).days
        if age_days < due_after_days:
            continue
        due.append(
            {
                **meta,
                "kind": "review",
                "strategy": "review_due",
                "age_days": age_days,
                "struggle_score": float(row["struggle_score"] or 0),
                "reason": _human_review_reason(age_days),
                "url": f"https://leetcode.cn/problems/{meta.get('slug')}/"
                if meta.get("slug")
                else f"/problems/{pid}",
                "problem_id": int(meta["id"]),
            }
        )
    due.sort(key=lambda x: (-float(x.get("struggle_score") or 0), -int(x["age_days"])))
    return due[:limit]


def pick_review_queue(
    conn: sqlite3.Connection,
    *,
    limit: int = 3,
    prefer_tags: list[str] | None = None,
) -> list[dict[str, Any]]:
    """今日复习队列：过滤 mastered 后扩容。"""
    mastered = mastered_problem_ids(conn)
    # 先召回较大池再过滤
    recall = max(limit, 10)
    cap = 50
    due: list[dict[str, Any]] = []
    while recall <= cap:
        due = list_review_due(conn, limit=recall)
        # list_review_due 已排除 mastered；再保险
        filtered = filter_expand(due, mastered, limit=limit, cap=cap)
        if len(filtered) >= limit or recall >= cap:
            due = filtered
            break
        recall = min(cap, recall * 2)

    if not due:
        return []

    prefer = [t for t in (prefer_tags or []) if t]
    picked: list[dict[str, Any]] = []
    picked_ids: set[int] = set()

    if prefer:
        for item in due:
            if len(picked) >= limit:
                break
            if item["id"] in picked_ids:
                continue
            if any(t in prefer for t in item.get("tags") or []):
                picked.append(item)
                picked_ids.add(int(item["id"]))

    for item in due:
        if len(picked) >= limit:
            break
        if item["id"] in picked_ids:
            continue
        picked.append(item)
        picked_ids.add(int(item["id"]))

    return picked[:limit]


def format_review_queue(candidates: list[dict[str, Any]]) -> str:
    if not candidates:
        return (
            "今天没有特别需要复习的旧题。"
            "可以去做「推荐下一题」练新题，或过几天再来。"
        )
    lines = ["今日复习："]
    for i, c in enumerate(candidates, 1):
        pid = c.get("problem_id") or c.get("id")
        title = c.get("title") or ""
        diff = c.get("difficulty") or ""
        reason = c.get("reason") or ""
        url = c.get("url") or f"/problems/{pid}"
        lines.append(f"{i}. {pid}. {title}（{diff}）— {reason}")
        lines.append(f"   链接：{url}")
    return "\n".join(lines)


def polish_review_prompt(candidates: list[dict[str, Any]], profile_summary: str) -> str:
    listing = format_review_queue(candidates)
    return (
        "用一两句口语提醒用户复习下列【旧题】，不要改题号，不要推荐未做过的新题。"
        "不要输出代码。\n\n"
        f"画像摘要：{profile_summary}\n\n"
        f"队列：\n{listing}\n"
    )
