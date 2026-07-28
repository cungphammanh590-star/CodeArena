"""按题目生成 popup / 扩展用陪练建议（模板，不调 LLM）。"""

from __future__ import annotations

import sqlite3
from typing import Any, Optional

from leetcode_tracker.coach.opening import template_opening
from leetcode_tracker.kg.queries import (
    format_kg_context_markdown,
    list_placements_for_problem,
    select_primary_placement,
)
from leetcode_tracker.core.problem_stats import get_problem_stats_row
from leetcode_tracker.core.submissions import (
    count_today_attempts_for_problem,
    get_latest_submission_for_problem,
)


def _stats_line(stats: Optional[dict[str, Any]]) -> str:
    if not stats:
        return "尚无提交记录；提交一次后我会根据结果陪你复盘。"
    total = int(stats.get("total_attempts") or 0)
    ac = int(stats.get("accepted_count") or 0)
    last = stats.get("last_status") or "—"
    struggle = float(stats.get("struggle_score") or 0)
    if total <= 0:
        return "尚无提交记录；提交一次后我会根据结果陪你复盘。"
    return (
        f"本题共 {total} 次尝试，AC {ac} 次，最近状态 {last}，"
        f"挣扎指数 {struggle:.2f}。"
    )


def build_problem_hint(conn: sqlite3.Connection, problem_id: int) -> dict[str, Any]:
    stats_row = get_problem_stats_row(conn, problem_id)
    latest = get_latest_submission_for_problem(conn, problem_id)
    placements = list_placements_for_problem(conn, problem_id)
    primary = select_primary_placement(placements)
    kg_md, _ = format_kg_context_markdown(conn, problem_id)

    title = (stats_row or {}).get("title") or f"Problem {problem_id}"
    difficulty = (stats_row or {}).get("difficulty")

    if latest:
        today_count = count_today_attempts_for_problem(conn, problem_id)
        suggestion = template_opening(
            problem_id=problem_id,
            title=str(latest.get("title") or title),
            status=str(latest["status"]),
            placement=primary,
            today_count=today_count,
        )
        coach_url_submission = str(latest["submission_id"])
        has_submission = True
    else:
        if primary:
            module = f"{primary.track_name} / {primary.submodule_name}"
            progress = f"{primary.accepted_in_node}/{primary.total_in_node}"
            ann = f"（{primary.annotation}）" if primary.annotation else ""
            suggestion = (
                f"{problem_id}. {title} 在路线 {module} 中排第 {primary.sort_order} 题{ann}，"
                f"子模块进度 {progress}。"
                f"{_stats_line(stats_row)}"
            )
        else:
            suggestion = (
                f"{problem_id}. {title} 不在 bundled 学习路线图内。"
                f"{_stats_line(stats_row)}"
            )
        coach_url_submission = None
        has_submission = False

    kg_short = ""
    if primary:
        kg_short = (
            f"{primary.track_name} → {primary.submodule_name} "
            f"({primary.accepted_in_node}/{primary.total_in_node} AC)"
        )
    elif placements:
        kg_short = "图谱外或多路线"
    else:
        kg_short = "图谱外"

    return {
        "problem_id": problem_id,
        "title": title,
        "difficulty": difficulty,
        "suggestion": suggestion,
        "kg_short": kg_short,
        "has_submission": has_submission,
        "latest_submission_id": coach_url_submission,
        "latest_status": latest.get("status") if latest else None,
        "stats_line": _stats_line(stats_row),
        "kg_markdown": kg_md,
    }
