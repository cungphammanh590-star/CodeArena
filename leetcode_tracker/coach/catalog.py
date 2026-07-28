"""题单（catalog）：多清单、Hot100 物化、JSON 导入。"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from pathlib import Path
from typing import Any

from leetcode_tracker.coach.hot100 import load_hot100
from leetcode_tracker.infra.config import get_learning_config, update_learning_config
from leetcode_tracker.infra.timeutil import china_now_iso

HOT100_LIST_ID = "hot100"
_HOT100_PATH = Path(__file__).resolve().parent / "data" / "hot100.json"

SAMPLE_LIST_JSON = {
    "problems": [
        {
            "id": 1,
            "slug": "two-sum",
            "title": "两数之和",
            "difficulty": "Easy",
            "tags": ["数组", "哈希表"],
            "order": 1,
        },
        {
            "id": 49,
            "slug": "group-anagrams",
            "title": "字母异位词分组",
            "difficulty": "Medium",
            "tags": ["数组", "哈希表", "字符串"],
            "order": 2,
        },
    ],
}

SAMPLE_SINGLE_JSON = {
    "problems": [
        {
            "id": 1,
            "slug": "two-sum",
            "title": "两数之和",
            "difficulty": "Easy",
            "tags": ["数组", "哈希表"],
            "order": 1,
        }
    ],
}


def ensure_catalog_schema(conn: sqlite3.Connection) -> None:
    # 表已在 SCHEMA；此处负责物化 Hot100
    ensure_hot100_materialized(conn)


def _hot100_fingerprint() -> str:
    try:
        raw = _HOT100_PATH.read_bytes()
    except OSError:
        return ""
    return hashlib.sha256(raw).hexdigest()[:32]


def ensure_hot100_materialized(conn: sqlite3.Connection) -> None:
    fp = _hot100_fingerprint()
    row = conn.execute(
        "SELECT id, updated_at FROM problem_lists WHERE id = ?",
        (HOT100_LIST_ID,),
    ).fetchone()
    meta_fp = conn.execute(
        "SELECT value FROM kg_meta WHERE key = ?",
        ("hot100_list_fingerprint",),
    ).fetchone()
    # 复用 kg_meta 存指纹略怪；改用 problem_lists.updated_at 比对 fp 存 source 字段旁注
    stored = ""
    if row:
        # fingerprint in a side key via app-like: check items count vs catalog
        pass
    need = row is None
    if not need and fp:
        # store fingerprint as list source suffix check via separate meta table not needed —
        # compare item count + first checksum in list name... use kg_meta for simplicity
        stored_row = conn.execute(
            "SELECT value FROM kg_meta WHERE key = 'hot100_list_fingerprint'"
        ).fetchone()
        stored = str(stored_row["value"]) if stored_row else ""
        need = stored != fp
    if not need and row is not None:
        return

    catalog = load_hot100()
    now = china_now_iso()
    conn.execute(
        """
        INSERT INTO problem_lists (id, name, source, readonly, created_at, updated_at)
        VALUES (?, ?, ?, 1, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            source = excluded.source,
            readonly = 1,
            updated_at = excluded.updated_at
        """,
        (HOT100_LIST_ID, "力扣热题 HOT 100", "bundled", now, now),
    )
    conn.execute(
        "DELETE FROM problem_list_items WHERE list_id = ?", (HOT100_LIST_ID,)
    )
    for p in catalog:
        conn.execute(
            """
            INSERT INTO problem_list_items
            (list_id, problem_id, slug, title, difficulty, tags_json, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                HOT100_LIST_ID,
                int(p["id"]),
                str(p.get("slug") or ""),
                str(p.get("title") or ""),
                str(p.get("difficulty") or ""),
                json.dumps(list(p.get("tags") or []), ensure_ascii=False),
                int(p.get("order") or 0),
            ),
        )
    if fp:
        conn.execute(
            "INSERT OR REPLACE INTO kg_meta (key, value) VALUES (?, ?)",
            ("hot100_list_fingerprint", fp),
        )
    conn.commit()


def list_problem_lists(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    ensure_hot100_materialized(conn)
    learning = get_learning_config()
    active = str(learning.get("active_list_id") or HOT100_LIST_ID)
    rows = conn.execute(
        """
        SELECT pl.id, pl.name, pl.source, pl.readonly, pl.created_at, pl.updated_at,
               (SELECT COUNT(*) FROM problem_list_items i WHERE i.list_id = pl.id) AS total
        FROM problem_lists pl
        ORDER BY pl.readonly DESC, pl.name
        """
    ).fetchall()
    out = []
    for r in rows:
        out.append(
            {
                "id": r["id"],
                "name": r["name"],
                "source": r["source"],
                "readonly": bool(r["readonly"]),
                "total": int(r["total"] or 0),
                "active": r["id"] == active,
                "created_at": r["created_at"],
                "updated_at": r["updated_at"],
            }
        )
    return out


def load_list_items(conn: sqlite3.Connection, list_id: str) -> list[dict[str, Any]]:
    ensure_hot100_materialized(conn)
    rows = conn.execute(
        """
        SELECT problem_id, slug, title, difficulty, tags_json, sort_order
        FROM problem_list_items
        WHERE list_id = ?
        ORDER BY sort_order, problem_id
        """,
        (list_id,),
    ).fetchall()
    out: list[dict[str, Any]] = []
    for r in rows:
        try:
            tags = json.loads(r["tags_json"] or "[]")
        except json.JSONDecodeError:
            tags = []
        if not isinstance(tags, list):
            tags = []
        out.append(
            {
                "id": int(r["problem_id"]),
                "slug": str(r["slug"] or ""),
                "title": str(r["title"] or ""),
                "title_cn": str(r["title"] or ""),
                "difficulty": str(r["difficulty"] or ""),
                "tags": [str(t) for t in tags if str(t).strip()],
                "order": int(r["sort_order"] or 0),
            }
        )
    return out


def load_active_catalog(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:
    ensure_hot100_materialized(conn)
    learning = get_learning_config()
    list_id = str(learning.get("active_list_id") or HOT100_LIST_ID)
    items = load_list_items(conn, list_id)
    if not items and list_id != HOT100_LIST_ID:
        list_id = HOT100_LIST_ID
        items = load_list_items(conn, list_id)
    return list_id, items


def catalog_by_id(conn: sqlite3.Connection) -> dict[int, dict[str, Any]]:
    _, items = load_active_catalog(conn)
    return {p["id"]: p for p in items}


def accepted_problem_ids(conn: sqlite3.Connection) -> set[int]:
    rows = conn.execute(
        "SELECT problem_id FROM problem_stats WHERE accepted_count > 0"
    ).fetchall()
    return {int(r["problem_id"]) for r in rows}


def catalog_progress(conn: sqlite3.Connection) -> dict[str, Any]:
    list_id, catalog = load_active_catalog(conn)
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
    from leetcode_tracker.coach.mastered import count_mastered_in_set

    mastered_n = count_mastered_in_set(conn, {p["id"] for p in catalog})
    return {
        "list_id": list_id,
        "done": len(done_ids),
        "total": total,
        "ratio": round(len(done_ids) / total, 3) if total else 0.0,
        "next_unsolved_id": int(next_unsolved["id"]) if next_unsolved else None,
        "next_unsolved_order": int(next_unsolved["order"]) if next_unsolved else None,
        "by_tag": by_tag,
        "mastered_count": mastered_n,
    }


def get_list_row(conn: sqlite3.Connection, list_id: str) -> dict[str, Any] | None:
    row = conn.execute(
        "SELECT id, name, source, readonly FROM problem_lists WHERE id = ?",
        (list_id,),
    ).fetchone()
    if row is None:
        return None
    return {
        "id": row["id"],
        "name": row["name"],
        "source": row["source"],
        "readonly": bool(row["readonly"]),
    }


def set_active_list(list_id: str) -> dict[str, Any]:
    return update_learning_config(active_list_id=list_id)


def restore_default_list() -> dict[str, Any]:
    return update_learning_config(active_list_id=HOT100_LIST_ID)


def suggest_list_id(name: str) -> str:
    import re
    import time

    raw = re.sub(r"[^a-zA-Z0-9_-]+", "-", (name or "").strip().lower()).strip("-_")
    if not raw or raw == HOT100_LIST_ID:
        raw = f"list-{int(time.time())}"
    return raw[:64]


def validate_list_payload(raw: Any) -> list[dict[str, Any]]:
    """校验题目 JSON；根可为 {problems:[...]} 或直接数组。忽略 _meta。"""
    if isinstance(raw, list):
        problems = raw
    elif isinstance(raw, dict):
        # 兼容旧样例：有 _meta 时忽略
        problems = raw.get("problems")
        if problems is None:
            raise ValueError('请提供 {"problems": [...]} 或题目数组')
    else:
        raise ValueError('请提供 {"problems": [...]} 或题目数组')

    if not isinstance(problems, list) or not problems:
        raise ValueError("problems 必须是非空数组")

    parsed: list[dict[str, Any]] = []
    seen: set[int] = set()
    for i, row in enumerate(problems, start=1):
        prefix = f"problems[{i}]"
        if not isinstance(row, dict):
            raise ValueError(f"{prefix} 必须是 object")
        if "id" not in row:
            raise ValueError(f"{prefix} 缺少 id")
        try:
            pid = int(row["id"])
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{prefix}.id 必须是正整数") from exc
        if pid <= 0:
            raise ValueError(f"{prefix}.id 必须是正整数")
        if "slug" not in row or not str(row.get("slug") or "").strip():
            raise ValueError(f"{prefix} 缺少 slug")
        title = row.get("title")
        title_cn = row.get("title_cn")
        if not (str(title or "").strip() or str(title_cn or "").strip()):
            raise ValueError(f"{prefix} 缺少 title 或 title_cn")
        if "order" not in row:
            raise ValueError(f"{prefix} 缺少 order")
        try:
            order = int(row["order"])
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{prefix}.order 必须是整数") from exc
        if "difficulty" not in row:
            raise ValueError(f"{prefix} 缺少 difficulty")
        if "tags" not in row:
            raise ValueError(f"{prefix} 缺少 tags（可为 []）")
        if not isinstance(row["tags"], list):
            raise ValueError(f"{prefix}.tags 必须是数组")
        if pid in seen:
            raise ValueError(f"{prefix} 题号 {pid} 在文件内重复")
        seen.add(pid)
        parsed.append(
            {
                "id": pid,
                "slug": str(row["slug"]).strip(),
                "title": str(title or title_cn).strip(),
                "difficulty": str(row["difficulty"] or "").strip(),
                "tags": [str(t).strip() for t in row["tags"] if str(t).strip()],
                "order": order,
            }
        )
    return parsed


def create_list(
    conn: sqlite3.Connection,
    *,
    list_id: str | None = None,
    name: str,
    source: str = "user",
) -> dict[str, Any]:
    display = (name or "").strip()
    if not display:
        raise ValueError("题单名称不能为空")
    lid = (list_id or "").strip() or suggest_list_id(display)
    if lid == HOT100_LIST_ID:
        raise ValueError("不能使用保留 id: hot100")
    if get_list_row(conn, lid):
        raise ValueError(f"题单已存在: {lid}")
    now = china_now_iso()
    conn.execute(
        """
        INSERT INTO problem_lists (id, name, source, readonly, created_at, updated_at)
        VALUES (?, ?, ?, 0, ?, ?)
        """,
        (lid, display, source, now, now),
    )
    conn.commit()
    return get_list_row(conn, lid) or {"id": lid, "name": display}


def remove_list_item(
    conn: sqlite3.Connection, list_id: str, problem_id: int
) -> dict[str, Any]:
    row = get_list_row(conn, list_id)
    if row is None:
        raise ValueError(f"题单不存在: {list_id}")
    if row.get("readonly"):
        raise ValueError("不能修改只读题单")
    cur = conn.execute(
        "DELETE FROM problem_list_items WHERE list_id = ? AND problem_id = ?",
        (list_id, int(problem_id)),
    )
    if cur.rowcount <= 0:
        raise ValueError("题单中无此题")
    conn.execute(
        "UPDATE problem_lists SET updated_at = ? WHERE id = ?",
        (china_now_iso(), list_id),
    )
    conn.commit()
    total = int(
        conn.execute(
            "SELECT COUNT(*) AS c FROM problem_list_items WHERE list_id = ?",
            (list_id,),
        ).fetchone()["c"]
    )
    return {"list_id": list_id, "problem_id": int(problem_id), "total": total}


def delete_list(conn: sqlite3.Connection, list_id: str) -> dict[str, Any]:
    row = get_list_row(conn, list_id)
    if row is None:
        raise ValueError(f"题单不存在: {list_id}")
    if row.get("readonly") or list_id == HOT100_LIST_ID:
        raise ValueError("不能删除只读题单")
    conn.execute("DELETE FROM problem_list_items WHERE list_id = ?", (list_id,))
    conn.execute("DELETE FROM problem_lists WHERE id = ?", (list_id,))
    conn.commit()
    learning = get_learning_config()
    switched = False
    if str(learning.get("active_list_id") or "") == list_id:
        restore_default_list()
        switched = True
    return {"list_id": list_id, "deleted": True, "restored_hot100": switched}


def import_list_json(
    conn: sqlite3.Connection,
    raw: Any,
    *,
    list_id: str,
    mode: str = "append",
    create_if_missing: bool = False,
    new_list_name: str | None = None,
) -> dict[str, Any]:
    problems = validate_list_payload(raw)
    mode = (mode or "append").strip().lower()
    if mode not in {"overwrite", "append"}:
        raise ValueError("mode 须为 overwrite 或 append")

    target = (list_id or "").strip()
    if not target:
        raise ValueError("请先选择或新建题单")
    if target == HOT100_LIST_ID:
        raise ValueError("不能写入只读 Hot100 题单")

    existing = get_list_row(conn, target)
    if existing and existing.get("readonly"):
        raise ValueError("不能修改只读题单")
    if existing is None:
        if not create_if_missing:
            raise ValueError(f"题单不存在: {target}")
        create_list(
            conn,
            list_id=target,
            name=(new_list_name or target).strip() or target,
            source="user",
        )
    elif new_list_name and str(new_list_name).strip():
        conn.execute(
            "UPDATE problem_lists SET name = ?, updated_at = ? WHERE id = ?",
            (str(new_list_name).strip(), china_now_iso(), target),
        )

    now = china_now_iso()
    added = 0
    skipped = 0
    if mode == "overwrite":
        conn.execute(
            "DELETE FROM problem_list_items WHERE list_id = ?", (target,)
        )
        for p in problems:
            conn.execute(
                """
                INSERT INTO problem_list_items
                (list_id, problem_id, slug, title, difficulty, tags_json, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    target,
                    p["id"],
                    p["slug"],
                    p["title"],
                    p["difficulty"],
                    json.dumps(p["tags"], ensure_ascii=False),
                    p["order"],
                ),
            )
            added += 1
    else:
        existing_ids = {
            int(r["problem_id"])
            for r in conn.execute(
                "SELECT problem_id FROM problem_list_items WHERE list_id = ?",
                (target,),
            ).fetchall()
        }
        max_order_row = conn.execute(
            "SELECT MAX(sort_order) AS m FROM problem_list_items WHERE list_id = ?",
            (target,),
        ).fetchone()
        next_order = int(max_order_row["m"] or 0) + 1
        for p in sorted(problems, key=lambda x: x["order"]):
            if p["id"] in existing_ids:
                skipped += 1
                continue
            conn.execute(
                """
                INSERT INTO problem_list_items
                (list_id, problem_id, slug, title, difficulty, tags_json, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    target,
                    p["id"],
                    p["slug"],
                    p["title"],
                    p["difficulty"],
                    json.dumps(p["tags"], ensure_ascii=False),
                    next_order,
                ),
            )
            next_order += 1
            existing_ids.add(p["id"])
            added += 1

    conn.execute(
        "UPDATE problem_lists SET updated_at = ? WHERE id = ?",
        (now, target),
    )
    conn.commit()
    total = int(
        conn.execute(
            "SELECT COUNT(*) AS c FROM problem_list_items WHERE list_id = ?",
            (target,),
        ).fetchone()["c"]
    )
    return {
        "list_id": target,
        "mode": mode,
        "added": added,
        "skipped_dupes": skipped,
        "total": total,
    }
