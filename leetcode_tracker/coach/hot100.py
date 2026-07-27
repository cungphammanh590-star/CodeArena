"""Hot100 静态清单加载与进度。"""

from __future__ import annotations

import json
import sqlite3
from functools import lru_cache
from pathlib import Path
from typing import Any

_HOT100_PATH = Path(__file__).resolve().parent / "data" / "hot100.json"


@lru_cache(maxsize=1)
def load_hot100() -> list[dict[str, Any]]:
    raw = json.loads(_HOT100_PATH.read_text(encoding="utf-8"))
    problems = raw.get("problems") if isinstance(raw, dict) else raw
    if not isinstance(problems, list):
        return []
    out: list[dict[str, Any]] = []
    for row in problems:
        if not isinstance(row, dict) or "id" not in row:
            continue
        out.append(
            {
                "id": int(row["id"]),
                "slug": str(row.get("slug") or ""),
                "title": str(row.get("title") or row.get("title_cn") or ""),
                "title_cn": str(row.get("title_cn") or row.get("title") or ""),
                "difficulty": str(row.get("difficulty") or ""),
                "tags": [str(t) for t in (row.get("tags") or []) if str(t).strip()],
                "order": int(row.get("order") or 0),
            }
        )
    out.sort(key=lambda p: (p["order"], p["id"]))
    return out


def hot100_by_id() -> dict[int, dict[str, Any]]:
    return {p["id"]: p for p in load_hot100()}


def accepted_problem_ids(conn: sqlite3.Connection) -> set[int]:
    rows = conn.execute(
        "SELECT problem_id FROM problem_stats WHERE accepted_count > 0"
    ).fetchall()
    return {int(r["problem_id"]) for r in rows}


def hot100_progress(conn: sqlite3.Connection) -> dict[str, Any]:
    catalog = load_hot100()
    total = len(catalog)
    solved = accepted_problem_ids(conn)
    done_ids = [p["id"] for p in catalog if p["id"] in solved]
    next_unsolved = next((p for p in catalog if p["id"] not in solved), None)
    by_tag: dict[str, int] = {}
    for p in catalog:
        if p["id"] not in solved:
            continue
        for tag in p["tags"]:
            by_tag[tag] = by_tag.get(tag, 0) + 1
    return {
        "list_id": "hot100",
        "done": len(done_ids),
        "total": total,
        "ratio": round(len(done_ids) / total, 3) if total else 0.0,
        "next_unsolved_id": int(next_unsolved["id"]) if next_unsolved else None,
        "next_unsolved_order": int(next_unsolved["order"]) if next_unsolved else None,
        "by_tag": by_tag,
    }
