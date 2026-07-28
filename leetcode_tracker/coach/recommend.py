"""共享推荐：活跃题单 + 知识图谱合并（不含复习）。"""

from __future__ import annotations

import sqlite3
from typing import Any, Optional

from leetcode_tracker.coach.catalog import (
    accepted_problem_ids,
    catalog_progress,
    load_active_catalog,
)
from leetcode_tracker.coach.mastered import filter_expand, mastered_problem_ids
from leetcode_tracker.infra.config import get_learning_config
from leetcode_tracker.infra.timeutil import china_now_iso
from leetcode_tracker.kg.import_maps import ensure_kg_imported, kg_is_imported
from leetcode_tracker.kg.queries import (
    kg_same_node_unsolved,
    kg_successors_unsolved,
    kg_weakest_node_unsolved,
)


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
    source: str,
    priority: int,
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
        "source": source,
        "priority": priority,
        "reason": reason,
        "url": f"https://leetcode.cn/problems/{p.get('slug')}/"
        if p.get("slug")
        else f"/problems/{p['id']}",
    }


def _meta_from_pid(conn: sqlite3.Connection, pid: int) -> dict[str, Any]:
    row = conn.execute(
        """
        SELECT problem_id, title, slug, difficulty, tags
        FROM problems WHERE problem_id = ?
        """,
        (pid,),
    ).fetchone()
    if row:
        tags_raw = row["tags"]
        tags: list[str] = []
        if tags_raw:
            import json

            try:
                parsed = json.loads(tags_raw)
                if isinstance(parsed, list):
                    tags = [str(t) for t in parsed]
            except json.JSONDecodeError:
                tags = [t.strip() for t in str(tags_raw).split(",") if t.strip()]
        return {
            "id": int(row["problem_id"]),
            "title": str(row["title"] or f"#{pid}"),
            "slug": str(row["slug"] or ""),
            "difficulty": str(row["difficulty"] or ""),
            "tags": tags,
            "order": 0,
        }
    ps = conn.execute(
        "SELECT title, title_slug, difficulty, topic_tags FROM problem_stats WHERE problem_id = ?",
        (pid,),
    ).fetchone()
    if ps:
        return {
            "id": pid,
            "title": str(ps["title"] or f"#{pid}"),
            "slug": str(ps["title_slug"] or ""),
            "difficulty": str(ps["difficulty"] or ""),
            "tags": [],
            "order": 0,
        }
    return {
        "id": pid,
        "title": f"#{pid}",
        "slug": "",
        "difficulty": "",
        "tags": [],
        "order": 0,
    }


def _merge_into(
    bucket: dict[int, dict[str, Any]], cand: dict[str, Any]
) -> None:
    pid = int(cand["id"])
    if pid <= 0:
        return
    if pid not in bucket:
        bucket[pid] = cand
        return
    old = bucket[pid]
    # 保留更高优先级（数字更小）
    if int(cand.get("priority") or 99) < int(old.get("priority") or 99):
        reasons = []
        for r in (cand.get("reason"), old.get("reason")):
            if r and r not in reasons:
                reasons.append(str(r))
        cand = dict(cand)
        cand["reason"] = "；".join(reasons)
        sources = {old.get("source"), cand.get("source")}
        if "list" in sources and "kg" in sources:
            cand["source"] = "both"
        bucket[pid] = cand
    else:
        reasons = []
        for r in (old.get("reason"), cand.get("reason")):
            if r and r not in reasons:
                reasons.append(str(r))
        old = dict(old)
        old["reason"] = "；".join(reasons)
        sources = {old.get("source"), cand.get("source")}
        if "list" in sources and "kg" in sources:
            old["source"] = "both"
        bucket[pid] = old


def recommend_problems(
    conn: sqlite3.Connection,
    *,
    weak_tags: list[str],
    limit: int = 3,
    prefer_difficulty: str = "Medium",  # noqa: ARG001
    current_tags: Optional[list[str]] = None,
    current_problem_id: Optional[int] = None,
) -> list[dict[str, Any]]:
    """合并推荐：题单级联 + 图谱后继/薄弱；过滤已掌握并扩容。"""
    learning = get_learning_config()
    list_mode = bool(learning.get("list_mode", True))
    kg_mode = bool(learning.get("kg_mode", True))

    if not list_mode and not kg_mode:
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
                "strategy": "modes_off",
                "kind": "message",
                "source": "",
                "priority": 0,
                "reason": "题单模式与知识图谱模式均已关闭。请在首页或维护台开启至少一种学习模式，或自由练习。",
                "url": "",
            }
        ]

    mastered = mastered_problem_ids(conn)
    solved = accepted_problem_ids(conn)
    recent = recent_recommended_ids(conn, days=7)
    list_id, catalog = load_active_catalog(conn) if list_mode else ("", [])
    progress = catalog_progress(conn) if list_mode else {
        "done": 0,
        "total": 0,
        "ratio": 0.0,
        "list_id": "",
    }

    ranked: list[dict[str, Any]] = []

    # --- list strategies (priority 1,2,5) ---
    if list_mode and catalog:
        unsolved = [
            p
            for p in catalog
            if p["id"] not in solved and p["id"] not in mastered
        ]
        if not unsolved and progress.get("ratio", 0) >= 0.9:
            # 可能仍有图谱候选；先记 message 备用
            pass
        elif unsolved:
            nxt = unsolved[0]
            ranked.append(
                _as_candidate(
                    nxt,
                    strategy="list_continue",
                    reason=(
                        f"题单续刷：进度 {progress['done']}/{progress['total']}，"
                        f"下一道（order {nxt['order']}）"
                    ),
                    source="list",
                    priority=1,
                )
            )
            focus_tags = [t for t in (current_tags or []) if t] or list(
                weak_tags or []
            )[:2]
            for p in unsolved:
                if p["id"] == nxt["id"] or p["id"] in recent:
                    continue
                overlap = [t for t in p["tags"] if t in focus_tags]
                if not overlap:
                    continue
                c = _as_candidate(
                    p,
                    strategy="same_tag",
                    reason=f"题单同标签：{', '.join(overlap)}",
                    source="list",
                    priority=2,
                )
                c["matched_tags"] = overlap
                ranked.append(c)
                break
            weak = [t for t in (weak_tags or []) if t]
            if weak:
                top = weak[0]
                for p in unsolved:
                    if p["id"] == nxt["id"] or p["id"] in recent:
                        continue
                    if top not in p["tags"]:
                        continue
                    if any(x["id"] == p["id"] for x in ranked):
                        continue
                    c = _as_candidate(
                        p,
                        strategy="weak_tag",
                        reason=f"题单薄弱补强：{top}",
                        source="list",
                        priority=2,
                    )
                    c["matched_tags"] = [top]
                    ranked.append(c)
                    break
            for p in unsolved:
                if any(x["id"] == p["id"] for x in ranked):
                    continue
                if p["id"] in recent:
                    continue
                ranked.append(
                    _as_candidate(
                        p,
                        strategy="fill",
                        reason="题单未完成补位",
                        source="list",
                        priority=5,
                    )
                )

    # --- kg strategies (priority 3,4) ---
    if kg_mode:
        try:
            ensure_kg_imported(conn)
        except Exception:  # noqa: BLE001
            pass
        if kg_is_imported(conn):
            if current_problem_id:
                for item in kg_successors_unsolved(
                    conn, int(current_problem_id), limit=20
                ):
                    pid = int(item["problem_id"])
                    if pid in solved or pid in mastered:
                        continue
                    meta = _meta_from_pid(conn, pid)
                    ranked.append(
                        _as_candidate(
                            meta,
                            strategy="kg_successor",
                            reason=item.get("reason")
                            or "图谱：同子模块后继",
                            source="kg",
                            priority=3,
                        )
                    )
                for item in kg_same_node_unsolved(
                    conn, int(current_problem_id), limit=20
                ):
                    pid = int(item["problem_id"])
                    if pid in solved or pid in mastered:
                        continue
                    meta = _meta_from_pid(conn, pid)
                    ranked.append(
                        _as_candidate(
                            meta,
                            strategy="kg_same_node",
                            reason=item.get("reason")
                            or "图谱：同子模块未完成",
                            source="kg",
                            priority=3,
                        )
                    )
            for item in kg_weakest_node_unsolved(conn, limit=20):
                pid = int(item["problem_id"])
                if pid in solved or pid in mastered:
                    continue
                meta = _meta_from_pid(conn, pid)
                ranked.append(
                    _as_candidate(
                        meta,
                        strategy="kg_weak",
                        reason=item.get("reason") or "图谱：最弱模块补位",
                        source="kg",
                        priority=4,
                    )
                )

    # 排序并合并同题
    ranked.sort(key=lambda x: (int(x.get("priority") or 99), int(x.get("order") or 0)))
    bucket: dict[int, dict[str, Any]] = {}
    for c in ranked:
        _merge_into(bucket, c)
    merged = sorted(
        bucket.values(),
        key=lambda x: (int(x.get("priority") or 99), int(x.get("order") or 0)),
    )

    # 扩容选取：先从 merged 取足够长，再 filter（已在构建时排除 mastered，再保险）
    cap = max(50, limit * 8)
    picked = filter_expand(merged, mastered, limit=limit, cap=cap)

    if not picked:
        if list_mode and catalog:
            unsolved_all = [p for p in catalog if p["id"] not in solved]
            if not unsolved_all and progress.get("ratio", 0) >= 0.9:
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
                        "source": "list",
                        "priority": 0,
                        "reason": (
                            f"题单新题已全部 AC（{progress['done']}/{progress['total']}）。"
                            "去做「今日复习」温习旧题，或按知识图谱专题深挖。"
                        ),
                        "url": "",
                    }
                ]
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
                "strategy": "empty",
                "kind": "message",
                "source": "",
                "priority": 0,
                "reason": "暂时没有可推荐的题目（可能已掌握或已完成）。可调整题单、取消掌握，或开启另一种学习模式。",
                "url": "",
            }
        ]

    if len(picked) < limit and len(merged) >= limit:
        # 文案侧：有几条推几条即可
        pass

    try:
        log_recommendations(conn, picked, list_id=list_id or "hot100")
    except Exception:  # noqa: BLE001
        pass

    return picked[:limit]


def format_recommendations_fallback(candidates: list[dict[str, Any]]) -> str:
    if not candidates:
        return (
            "暂时没有可推荐的题目。"
            "若清单已全部 AC，请用「今日复习」温习旧题。"
        )
    if len(candidates) == 1 and candidates[0].get("kind") == "message":
        return str(candidates[0].get("reason") or "")

    lines = ["推荐下一题（不含复习）："]
    for i, c in enumerate(candidates, 1):
        pid = c.get("problem_id") or c.get("id")
        title = c.get("title") or ""
        diff = c.get("difficulty") or ""
        reason = c.get("reason") or ""
        src = c.get("source") or ""
        url = c.get("url") or f"/problems/{pid}"
        src_bit = f"[{src}] " if src else ""
        lines.append(f"{i}. {src_bit}{pid}. {title}（{diff}）— {reason}")
        lines.append(f"   链接：{url}")
    return "\n".join(lines)


def polish_prompt(candidates: list[dict[str, Any]], profile_summary: str) -> str:
    listing = format_recommendations_fallback(candidates)
    return (
        "用一两句口语把下列【新题】推荐说给刷题用户听，不要改题号，"
        "不要混入复习旧题。说明推荐理由即可。不要输出代码。\n\n"
        f"画像摘要：{profile_summary}\n\n"
        f"候选：\n{listing}\n"
    )
