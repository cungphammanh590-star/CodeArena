"""共享推荐子图核心：纯 DB/规则选题，零 LLM。"""

from __future__ import annotations

import json
import sqlite3
from typing import Any


def _parse_tags(raw: Any) -> list[str]:
    if raw is None or raw == "":
        return []
    if isinstance(raw, (list, tuple)):
        return [str(x).strip() for x in raw if str(x).strip()]
    text = str(raw).strip()
    try:
        value = json.loads(text)
        if isinstance(value, list):
            return [str(x).strip() for x in value if str(x).strip()]
    except json.JSONDecodeError:
        pass
    return [t.strip() for t in text.split(",") if t.strip()]


def _diff_rank(difficulty: str) -> int:
    low = (difficulty or "").lower()
    if "easy" in low or "简单" in (difficulty or ""):
        return 1
    if "hard" in low or "困难" in (difficulty or ""):
        return 3
    if "medium" in low or "中等" in (difficulty or ""):
        return 2
    return 2


def recommend_problems(
    conn: sqlite3.Connection,
    *,
    weak_tags: list[str],
    limit: int = 3,
    prefer_difficulty: str = "Medium",
) -> list[dict[str, Any]]:
    """弱标签 ∩ 未 AC（或从未做过）∩ 难度梯度，规则排序 Top-N。"""
    weak = [t for t in (weak_tags or []) if t]
    prefer = _diff_rank(prefer_difficulty)
    rows = conn.execute(
        """
        SELECT p.problem_id, p.title, p.difficulty, p.tags, p.slug,
               COALESCE(ps.accepted_count, 0) AS accepted_count,
               COALESCE(ps.struggle_score, 0) AS struggle_score,
               COALESCE(ps.total_attempts, 0) AS total_attempts
        FROM problems p
        LEFT JOIN problem_stats ps ON ps.problem_id = p.problem_id
        WHERE COALESCE(ps.accepted_count, 0) = 0
        ORDER BY p.problem_id ASC
        LIMIT 800
        """
    ).fetchall()
    scored: list[tuple[float, dict[str, Any]]] = []
    for row in rows:
        tags = _parse_tags(row["tags"])
        overlap = [t for t in tags if t in weak]
        if weak and not overlap:
            # 无弱标签命中时仍可收兜底候选，但分数更低
            tag_score = 0.0
        else:
            tag_score = float(len(overlap)) * 3.0 + (1.0 if overlap else 0.0)
        d_rank = _diff_rank(str(row["difficulty"] or ""))
        diff_score = 2.0 - abs(d_rank - prefer) * 0.8
        # 未尝试过略优先于反复错题
        attempts = int(row["total_attempts"] or 0)
        attempt_score = 1.0 if attempts == 0 else 0.4
        score = tag_score + diff_score + attempt_score
        scored.append(
            (
                score,
                {
                    "problem_id": int(row["problem_id"]),
                    "title": str(row["title"] or ""),
                    "difficulty": str(row["difficulty"] or ""),
                    "slug": str(row["slug"] or ""),
                    "tags": tags,
                    "matched_tags": overlap,
                    "reason": (
                        f"命中薄弱标签 {', '.join(overlap)}"
                        if overlap
                        else "未 AC 候选（按难度梯度）"
                    ),
                },
            )
        )
    scored.sort(key=lambda x: (-x[0], x[1]["problem_id"]))
    return [item for _, item in scored[:limit]]


def format_recommendations_fallback(candidates: list[dict[str, Any]]) -> str:
    if not candidates:
        return "暂时没有合适的未 AC 候选。可以先在题库多提交几道题，再来要推荐。"
    lines = ["根据你的画像，推荐下面几道题（规则选题，非模型编造）："]
    for i, c in enumerate(candidates, 1):
        pid = c["problem_id"]
        title = c.get("title") or ""
        diff = c.get("difficulty") or ""
        reason = c.get("reason") or ""
        lines.append(f"{i}. {pid}. {title}（{diff}）— {reason}")
        lines.append(f"   详情：/problems/{pid}")
    return "\n".join(lines)


def polish_prompt(candidates: list[dict[str, Any]], profile_summary: str) -> str:
    listing = format_recommendations_fallback(candidates)
    return (
        "用一两句口语把下列推荐说给刷题用户听，不要改题号，不要增加题库外的题。"
        "不要输出代码。\n\n"
        f"画像摘要：{profile_summary}\n\n"
        f"候选：\n{listing}\n"
    )
