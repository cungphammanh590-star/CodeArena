"""将 algorithm-stone 地图导入 SQLite。"""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any

from leetcode_tracker.kg.parser import bundled_maps_dir, parse_map_file
from leetcode_tracker.infra.timeutil import china_now_iso


def _clear_kg_tables(conn: sqlite3.Connection) -> None:
    conn.execute("DELETE FROM kg_edges")
    conn.execute("DELETE FROM kg_node_problems")
    conn.execute("DELETE FROM kg_nodes")
    conn.execute("DELETE FROM kg_tracks")
    conn.execute("DELETE FROM kg_meta")


def import_maps(
    conn: sqlite3.Connection,
    maps_dir: Path | None = None,
    *,
    source: str = "algorithm-stone",
) -> dict[str, Any]:
    root = maps_dir or bundled_maps_dir()
    if not root.is_dir():
        raise FileNotFoundError(f"图谱 map 目录不存在: {root}")

    files = sorted(root.glob("leetcode-*.txt"))
    if not files:
        raise FileNotFoundError(f"未找到 map 文件: {root}/leetcode-*.txt")

    _clear_kg_tables(conn)
    track_order = 0
    total_problems: set[int] = set()
    total_edges = 0
    total_nodes = 0

    for map_file in files:
        track_order += 1
        parsed = parse_map_file(map_file)
        track_problem_ids: set[int] = set()
        conn.execute(
            """
            INSERT INTO kg_tracks (id, name, source, problem_count, sort_order)
            VALUES (?, ?, ?, 0, ?)
            """,
            (parsed.track_id, parsed.track_name, source, track_order),
        )

        for node in parsed.nodes:
            total_nodes += 1
            node_id = f"{parsed.track_id}::{node.sort_order}::{node.submodule_name}"
            conn.execute(
                """
                INSERT INTO kg_nodes (id, track_id, name, sort_order)
                VALUES (?, ?, ?, ?)
                """,
                (node_id, parsed.track_id, node.submodule_name, node.sort_order),
            )
            last_pid: int | None = None
            seen_in_node: set[int] = set()
            for order, problem in enumerate(node.problems, start=1):
                if problem.problem_id in seen_in_node:
                    continue
                seen_in_node.add(problem.problem_id)
                track_problem_ids.add(problem.problem_id)
                total_problems.add(problem.problem_id)
                conn.execute(
                    """
                    INSERT INTO kg_node_problems (node_id, problem_id, sort_order, annotation)
                    VALUES (?, ?, ?, ?)
                    """,
                    (node_id, problem.problem_id, order, problem.annotation),
                )
                if last_pid is not None:
                    conn.execute(
                        """
                        INSERT INTO kg_edges (from_problem_id, to_problem_id, node_id)
                        VALUES (?, ?, ?)
                        """,
                        (last_pid, problem.problem_id, node_id),
                    )
                    total_edges += 1
                last_pid = problem.problem_id

        conn.execute(
            "UPDATE kg_tracks SET problem_count = ? WHERE id = ?",
            (len(track_problem_ids), parsed.track_id),
        )

    now = china_now_iso()
    conn.execute(
        "INSERT INTO kg_meta (key, value) VALUES (?, ?)",
        ("imported_at", now),
    )
    conn.execute(
        "INSERT INTO kg_meta (key, value) VALUES (?, ?)",
        ("source", source),
    )
    conn.execute(
        "INSERT INTO kg_meta (key, value) VALUES (?, ?)",
        ("maps_dir", str(root)),
    )
    conn.commit()

    return {
        "tracks": len(files),
        "nodes": total_nodes,
        "problems": len(total_problems),
        "edges": total_edges,
        "source": source,
        "imported_at": now,
    }


def kg_is_imported(conn: sqlite3.Connection) -> bool:
    row = conn.execute("SELECT COUNT(*) AS c FROM kg_tracks").fetchone()
    return bool(row and int(row["c"]) > 0)


def _bundled_fingerprint() -> str:
    root = bundled_maps_dir()
    if not root.is_dir():
        return ""
    parts: list[str] = []
    for path in sorted(root.glob("leetcode-*.txt")):
        try:
            st = path.stat()
            parts.append(f"{path.name}:{st.st_mtime_ns}:{st.st_size}")
        except OSError:
            continue
    return "|".join(parts)


def ensure_kg_imported(conn: sqlite3.Connection) -> dict[str, Any]:
    """空库或 bundled maps 指纹变化时静默写入；失败不抛给调用方（由调用方决定）。"""
    fp = _bundled_fingerprint()
    meta = {
        row["key"]: row["value"]
        for row in conn.execute("SELECT key, value FROM kg_meta").fetchall()
    }
    need = (not kg_is_imported(conn)) or (
        bool(fp) and meta.get("bundled_fingerprint") != fp
    )
    if not need:
        return {"ensured": False, "imported": True, **get_kg_status(conn)}
    result = import_maps(conn)
    if fp:
        conn.execute(
            "INSERT OR REPLACE INTO kg_meta (key, value) VALUES (?, ?)",
            ("bundled_fingerprint", fp),
        )
        conn.commit()
    return {"ensured": True, **result}


def get_kg_status(conn: sqlite3.Connection) -> dict[str, Any]:
    tracks = int(conn.execute("SELECT COUNT(*) AS c FROM kg_tracks").fetchone()["c"])
    nodes = int(conn.execute("SELECT COUNT(*) AS c FROM kg_nodes").fetchone()["c"])
    problems = int(
        conn.execute("SELECT COUNT(DISTINCT problem_id) AS c FROM kg_node_problems").fetchone()["c"]
    )
    edges = int(conn.execute("SELECT COUNT(*) AS c FROM kg_edges").fetchone()["c"])
    meta = {
        row["key"]: row["value"]
        for row in conn.execute("SELECT key, value FROM kg_meta").fetchall()
    }
    return {
        "imported": tracks > 0,
        "tracks": tracks,
        "nodes": nodes,
        "problems": problems,
        "edges": edges,
        "meta": meta,
    }
