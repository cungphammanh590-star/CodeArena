"""本地「看思路」答案出口：可插拔数据源（无 RAG）。"""

from __future__ import annotations

import json
import sqlite3
from functools import lru_cache
from pathlib import Path
from typing import Any, Optional

from leetcode_tracker.kg.queries import list_placements_for_problem, select_primary_placement

_CURATED_PATH = Path(__file__).resolve().parent / "data" / "answer_skeletons.json"

GENERIC_SKELETONS = {
    "easy": (
        "通用 Easy 提纲：先用自己的话复述题意与输入输出；写出 1–2 个最小例子；"
        "确认暴力做法能否在数据范围内过；再看能否用一次遍历或简单哈希去掉一层循环。"
        "先别写完整题解代码，先回答：你卡在「读懂题」还是「想到做法但写不对」？"
    ),
    "medium": (
        "通用 Medium 提纲：列出需要维护的状态（下标、窗口、dp 维）；"
        "写清状态转移或指针移动规则；用一个会 WA 的边界例子推演一遍；"
        "最后才考虑优化常数。先说明你当前解法里「哪个不变量」你最没把握。"
    ),
    "hard": (
        "通用 Hard 提纲：把问题拆成已知子问题；画清决策树或分层结构；"
        "先保证正确性再谈复杂度。先用三句话说明：子问题是什么、如何合并、基础情况是什么。"
    ),
}


@lru_cache(maxsize=1)
def _load_curated() -> dict[str, str]:
    if not _CURATED_PATH.is_file():
        return {}
    try:
        data = json.loads(_CURATED_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return {str(k): str(v) for k, v in data.items() if isinstance(v, str)}


def get_latest_accepted(
    conn: sqlite3.Connection, problem_id: int
) -> Optional[dict[str, Any]]:
    row = conn.execute(
        """
        SELECT submission_id, code, language, submitted_at
        FROM submissions
        WHERE problem_id = ? AND status = 'Accepted'
        ORDER BY submitted_at DESC, id DESC
        LIMIT 1
        """,
        (int(problem_id),),
    ).fetchone()
    return dict(row) if row else None


def _difficulty_bucket(conn: sqlite3.Connection, problem_id: int) -> str:
    row = conn.execute(
        "SELECT difficulty FROM problems WHERE problem_id = ?",
        (int(problem_id),),
    ).fetchone()
    diff = str((row["difficulty"] if row else "") or "").lower()
    if "hard" in diff or "困难" in diff:
        return "hard"
    if "medium" in diff or "中等" in diff:
        return "medium"
    return "easy"


def _kg_annotation_skeleton(conn: sqlite3.Connection, problem_id: int) -> str:
    placements = list_placements_for_problem(conn, problem_id)
    primary = select_primary_placement(placements)
    if primary is None:
        return ""
    parts = [
        f"图谱位置：{primary.track_name} → {primary.submodule_name}（序位 {primary.sort_order}）。"
    ]
    if primary.annotation:
        parts.append(f"题库标注要点：{primary.annotation}。")
    parts.append(
        "请先对照标注，用自己的话写出「本题要维护什么状态」；"
        "不要急着贴完整代码。"
    )
    return "".join(parts)


def get_curated_skeleton(problem_id: int) -> str:
    return _load_curated().get(str(int(problem_id)), "").strip()


def build_answer_egress(
    conn: sqlite3.Connection,
    problem_id: int,
    *,
    degraded: bool = False,
) -> dict[str, Any]:
    """返回 {text, source}。source: history_ac | curated | kg_annotation | generic。"""
    prefix = (
        "你在这题上卡得有点久了，先看标准思路再回头改。\n\n"
        if degraded
        else ""
    )
    ac = get_latest_accepted(conn, problem_id)
    if ac and str(ac.get("code") or "").strip():
        lang = str(ac.get("language") or "text")
        code = str(ac["code"])
        # 历史 AC：允许展示用户自己的代码（标明来源）
        text = (
            f"{prefix}来源：你本人的历史 Accepted 提交"
            f"（{ac.get('submitted_at') or ''}）。\n"
            f"对照这段代码，找你当前提交里不一致的地方；不要盲抄。\n\n"
            f"```{lang}\n{code}\n```"
        )
        return {"text": text, "source": "history_ac"}

    curated = _load_curated().get(str(int(problem_id)), "").strip()
    if curated:
        return {"text": prefix + curated, "source": "curated"}

    kg_text = _kg_annotation_skeleton(conn, problem_id).strip()
    if kg_text:
        return {"text": prefix + kg_text, "source": "kg_annotation"}

    bucket = _difficulty_bucket(conn, problem_id)
    generic = GENERIC_SKELETONS.get(bucket, GENERIC_SKELETONS["medium"])
    return {
        "text": prefix + f"（通用 {bucket} 提纲，本题暂无专属骨架）\n" + generic,
        "source": "generic",
    }


class AnswerSource:  # 预留 RAG
    """后续向量题解检索可实现 retrieve(problem_id) -> str。"""

    def retrieve(self, problem_id: int) -> Optional[str]:  # noqa: ARG002
        return None
