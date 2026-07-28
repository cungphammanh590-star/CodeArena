"""SQLite 连接与 schema。"""

from __future__ import annotations

import sqlite3
from pathlib import Path

from leetcode_tracker.infra.paths import db_path, ensure_parent

SCHEMA = """
CREATE TABLE IF NOT EXISTS problems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL UNIQUE,
    title TEXT NOT NULL,
    slug TEXT NOT NULL,
    difficulty TEXT CHECK(difficulty IN ('Easy', 'Medium', 'Hard')),
    tags TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS submissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    submission_id TEXT NOT NULL UNIQUE,
    problem_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    code TEXT,
    runtime_ms INTEGER,
    memory_mb REAL,
    language TEXT,
    submitted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (problem_id) REFERENCES problems(problem_id)
);

CREATE INDEX IF NOT EXISTS idx_submissions_problem_id ON submissions(problem_id);
CREATE INDEX IF NOT EXISTS idx_submissions_submitted_at ON submissions(submitted_at);
CREATE INDEX IF NOT EXISTS idx_submissions_status ON submissions(status);

CREATE TABLE IF NOT EXISTS problem_stats (
    problem_id INTEGER PRIMARY KEY REFERENCES problems(problem_id),
    title TEXT,
    title_slug TEXT,
    difficulty TEXT,
    topic_tags TEXT,
    total_attempts INTEGER DEFAULT 0,
    accepted_count INTEGER DEFAULT 0,
    wrong_count INTEGER DEFAULT 0,
    status_breakdown TEXT,
    first_attempt_at DATETIME,
    last_attempt_at DATETIME,
    first_accepted_at DATETIME,
    acceptance_rate REAL DEFAULT 0.0,
    struggle_score REAL DEFAULT 0.0,
    solve_time_seconds INTEGER,
    avg_attempts_to_ac REAL,
    attempts_at_last_ac INTEGER DEFAULT 0,
    last_status TEXT,
    last_submitted_at DATETIME,
    llm_summary TEXT,
    common_pitfall TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS problem_daily_stats (
    problem_id INTEGER NOT NULL REFERENCES problems(problem_id),
    day TEXT NOT NULL,
    attempts INTEGER DEFAULT 0,
    accepted_today INTEGER DEFAULT 0,
    wrong_today INTEGER DEFAULT 0,
    status_breakdown TEXT,
    consecutive_days INTEGER DEFAULT 0,
    is_new_today INTEGER DEFAULT 0,
    is_review_today INTEGER DEFAULT 0,
    status_change TEXT,
    PRIMARY KEY (problem_id, day)
);

CREATE INDEX IF NOT EXISTS idx_problem_daily_day ON problem_daily_stats(day);

CREATE TABLE IF NOT EXISTS kg_tracks (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    source TEXT DEFAULT 'algorithm-stone',
    problem_count INTEGER DEFAULT 0,
    sort_order INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS kg_nodes (
    id TEXT PRIMARY KEY,
    track_id TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER DEFAULT 0,
    FOREIGN KEY (track_id) REFERENCES kg_tracks(id)
);

CREATE TABLE IF NOT EXISTS kg_node_problems (
    node_id TEXT NOT NULL,
    problem_id INTEGER NOT NULL,
    sort_order INTEGER NOT NULL,
    annotation TEXT,
    PRIMARY KEY (node_id, problem_id),
    FOREIGN KEY (node_id) REFERENCES kg_nodes(id)
);

CREATE TABLE IF NOT EXISTS kg_edges (
    from_problem_id INTEGER NOT NULL,
    to_problem_id INTEGER NOT NULL,
    node_id TEXT NOT NULL,
    PRIMARY KEY (from_problem_id, to_problem_id, node_id),
    FOREIGN KEY (node_id) REFERENCES kg_nodes(id)
);

CREATE TABLE IF NOT EXISTS kg_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_kg_node_problems_pid ON kg_node_problems(problem_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_from ON kg_edges(from_problem_id);

CREATE TABLE IF NOT EXISTS problem_lists (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    source TEXT DEFAULT 'user',
    readonly INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS problem_list_items (
    list_id TEXT NOT NULL,
    problem_id INTEGER NOT NULL,
    slug TEXT NOT NULL,
    title TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    tags_json TEXT NOT NULL DEFAULT '[]',
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (list_id, problem_id),
    FOREIGN KEY (list_id) REFERENCES problem_lists(id)
);

CREATE INDEX IF NOT EXISTS idx_problem_list_items_order
    ON problem_list_items(list_id, sort_order);

CREATE TABLE IF NOT EXISTS user_problem_flags (
    problem_id INTEGER PRIMARY KEY,
    mastered INTEGER NOT NULL DEFAULT 0,
    mastered_at TEXT,
    note TEXT
);
"""


def connect(path: Path | None = None) -> sqlite3.Connection:
    target = path or db_path()
    ensure_parent(target)
    conn = sqlite3.connect(str(target), timeout=5.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 5000")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(conn: sqlite3.Connection | None = None) -> sqlite3.Connection:
    own = conn is None
    if own:
        conn = connect()
    assert conn is not None
    conn.executescript(SCHEMA)
    conn.commit()
    # 业务时间统一中国时区；旧 UTC 数据一次性迁移
    from leetcode_tracker.infra.migrate_tz import ensure_china_timestamps

    ensure_china_timestamps(conn)
    try:
        from leetcode_tracker.coach.catalog import ensure_catalog_schema

        ensure_catalog_schema(conn)
    except Exception:  # noqa: BLE001
        pass
    return conn
