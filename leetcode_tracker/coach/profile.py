"""用户画像只读聚合（跨题 stats → CoachState）。"""

from __future__ import annotations

import json
import sqlite3
from collections import defaultdict
from typing import Any

from leetcode_tracker.infra.timeutil import china_today


def _parse_tags(raw: Any) -> list[str]:
    if raw is None or raw == "":
        return []
    if isinstance(raw, (list, tuple)):
        return [str(x).strip() for x in raw if str(x).strip()]
    text = str(raw).strip()
    if not text:
        return []
    try:
        value = json.loads(text)
        if isinstance(value, list):
            return [str(x).strip() for x in value if str(x).strip()]
    except json.JSONDecodeError:
        pass
    return [t.strip() for t in text.split(",") if t.strip()]


def _empty_profile() -> dict[str, Any]:
    return {
        "total_solved": 0,
        "easy_ratio": 0.0,
        "medium_ratio": 0.0,
        "hard_ratio": 0.0,
        "weak_tags": [],
        "recent_attempts": [],
        "today": {
            "day": china_today().isoformat(),
            "attempts": 0,
            "accepted": 0,
            "wrong": 0,
            "acceptance_rate": 0.0,
            "slowest_tag": "",
            "problems": [],
        },
        "summary_text": "暂无足够刷题数据，画像为空。",
    }


def build_user_profile(conn: sqlite3.Connection, *, recent_limit: int = 5) -> dict[str, Any]:
    """从 problem_stats / problem_daily_stats / submissions 聚合只读画像。"""
    profile = _empty_profile()
    day = china_today().isoformat()
    profile["today"]["day"] = day

    solved_rows = conn.execute(
        """
        SELECT difficulty, COUNT(*) AS n
        FROM problem_stats
        WHERE accepted_count > 0
        GROUP BY difficulty
        """
    ).fetchall()
    by_diff: dict[str, int] = {}
    total_solved = 0
    for row in solved_rows:
        key = str(row["difficulty"] or "").strip() or "Unknown"
        n = int(row["n"] or 0)
        by_diff[key] = n
        total_solved += n
    profile["total_solved"] = total_solved
    if total_solved > 0:
        easy = medium = hard = 0
        for k, v in by_diff.items():
            low = k.lower()
            if "easy" in low or "简单" in k:
                easy += v
            elif "hard" in low or "困难" in k:
                hard += v
            elif "medium" in low or "中等" in k:
                medium += v
        profile["easy_ratio"] = round(easy / total_solved, 3)
        profile["medium_ratio"] = round(medium / total_solved, 3)
        profile["hard_ratio"] = round(hard / total_solved, 3)

    # 弱标签：高 struggle × 有提交
    tag_scores: dict[str, list[float]] = defaultdict(list)
    for row in conn.execute(
        """
        SELECT topic_tags, struggle_score, acceptance_rate, accepted_count, total_attempts
        FROM problem_stats
        WHERE total_attempts > 0
        """
    ).fetchall():
        struggle = float(row["struggle_score"] or 0)
        accept = float(row["acceptance_rate"] or 0)
        weight = struggle + (1.0 - accept) * 0.5
        if int(row["accepted_count"] or 0) == 0 and int(row["total_attempts"] or 0) >= 2:
            weight += 0.4
        for tag in _parse_tags(row["topic_tags"]):
            tag_scores[tag].append(weight)

    ranked = sorted(
        ((tag, sum(scores) / len(scores)) for tag, scores in tag_scores.items() if scores),
        key=lambda x: x[1],
        reverse=True,
    )
    profile["weak_tags"] = [t for t, _ in ranked[:5]]

    recent = conn.execute(
        """
        SELECT s.problem_id, p.title, s.status, s.submitted_at
        FROM submissions s
        LEFT JOIN problems p ON p.problem_id = s.problem_id
        ORDER BY s.submitted_at DESC, s.id DESC
        LIMIT ?
        """,
        (recent_limit,),
    ).fetchall()
    profile["recent_attempts"] = [
        {
            "problem_id": int(r["problem_id"]),
            "title": str(r["title"] or ""),
            "status": str(r["status"] or ""),
            "submitted_at": str(r["submitted_at"] or ""),
        }
        for r in recent
    ]

    daily = conn.execute(
        """
        SELECT
            COALESCE(SUM(attempts), 0) AS attempts,
            COALESCE(SUM(accepted_today), 0) AS accepted,
            COALESCE(SUM(wrong_today), 0) AS wrong
        FROM problem_daily_stats
        WHERE day = ?
        """,
        (day,),
    ).fetchone()
    attempts = int(daily["attempts"] or 0) if daily else 0
    accepted = int(daily["accepted"] or 0) if daily else 0
    wrong = int(daily["wrong"] or 0) if daily else 0
    rate = round(accepted / attempts, 3) if attempts else 0.0
    profile["today"].update(
        {
            "attempts": attempts,
            "accepted": accepted,
            "wrong": wrong,
            "acceptance_rate": rate,
        }
    )

    today_problems = conn.execute(
        """
        SELECT d.problem_id, p.title, p.tags, d.attempts, d.accepted_today, d.wrong_today,
               d.status_change
        FROM problem_daily_stats d
        LEFT JOIN problems p ON p.problem_id = d.problem_id
        WHERE d.day = ?
        ORDER BY d.attempts DESC, d.problem_id ASC
        LIMIT 20
        """,
        (day,),
    ).fetchall()
    problems_out: list[dict[str, Any]] = []
    tag_wrong: dict[str, int] = defaultdict(int)
    for r in today_problems:
        tags = _parse_tags(r["tags"])
        problems_out.append(
            {
                "problem_id": int(r["problem_id"]),
                "title": str(r["title"] or ""),
                "attempts": int(r["attempts"] or 0),
                "accepted_today": int(r["accepted_today"] or 0),
                "wrong_today": int(r["wrong_today"] or 0),
                "status_change": str(r["status_change"] or ""),
                "tags": tags,
            }
        )
        if int(r["wrong_today"] or 0) > 0:
            for t in tags:
                tag_wrong[t] += int(r["wrong_today"] or 0)
    profile["today"]["problems"] = problems_out
    if tag_wrong:
        profile["today"]["slowest_tag"] = max(tag_wrong.items(), key=lambda x: x[1])[0]

    bits = [
        f"已 AC {total_solved} 题",
        f"今日提交 {attempts}、AC {accepted}、正确率 {int(rate * 100)}%",
    ]
    if profile["weak_tags"]:
        bits.append("薄弱标签：" + "、".join(profile["weak_tags"][:3]))
    if profile["today"]["slowest_tag"]:
        bits.append(f"今日错题偏多标签：{profile['today']['slowest_tag']}")
    profile["summary_text"] = "；".join(bits) + "。"
    return profile


def profile_prompt_block(profile: dict[str, Any] | None) -> str:
    if not profile:
        return ""
    weak = "、".join(profile.get("weak_tags") or []) or "（暂无）"
    today = profile.get("today") or {}
    return (
        "## 用户画像（只读）\n"
        f"- 摘要：{profile.get('summary_text') or '—'}\n"
        f"- 薄弱标签：{weak}\n"
        f"- 今日：尝试 {today.get('attempts', 0)}，AC {today.get('accepted', 0)}，"
        f"正确率 {int(float(today.get('acceptance_rate') or 0) * 100)}%\n"
    )
