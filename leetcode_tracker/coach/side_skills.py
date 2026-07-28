"""日级旁路技能：今日总结 / 复习 / 推荐。

不进入 LangGraph messages；库指纹未变则读缓存。
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from typing import Any, Optional

from leetcode_tracker.coach.daily_review import (
    assemble_daily_facts,
    daily_review_api_prompt,
    format_daily_review_local,
)
from leetcode_tracker.coach.graphs.common import GenerationCancelled, stream_model_reply
from leetcode_tracker.coach.recommend import (
    format_recommendations_fallback,
    polish_prompt,
    recommend_problems,
)
from leetcode_tracker.coach.review import (
    format_review_queue,
    pick_review_queue,
    polish_review_prompt,
)
from leetcode_tracker.infra.timeutil import china_now_iso, china_today


SIDE_ACTIONS = frozenset({"daily_review", "review", "recommend"})


def ensure_side_cache_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS coach_side_cache (
            kind TEXT NOT NULL,
            day TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            reply TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (kind, day)
        )
        """
    )
    conn.commit()


def _fingerprint(payload: Any) -> str:
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:40]


def _get_cache(
    conn: sqlite3.Connection, *, kind: str, day: str, fingerprint: str
) -> Optional[str]:
    ensure_side_cache_schema(conn)
    row = conn.execute(
        """
        SELECT reply, fingerprint FROM coach_side_cache
        WHERE kind = ? AND day = ?
        """,
        (kind, day),
    ).fetchone()
    if not row:
        return None
    if str(row["fingerprint"]) != fingerprint:
        return None
    text = str(row["reply"] or "").strip()
    return text or None


def _put_cache(
    conn: sqlite3.Connection,
    *,
    kind: str,
    day: str,
    fingerprint: str,
    reply: str,
) -> None:
    ensure_side_cache_schema(conn)
    conn.execute(
        """
        INSERT INTO coach_side_cache (kind, day, fingerprint, reply, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(kind, day) DO UPDATE SET
            fingerprint = excluded.fingerprint,
            reply = excluded.reply,
            updated_at = excluded.updated_at
        """,
        (kind, day, fingerprint, reply, china_now_iso()),
    )
    conn.commit()


def _current_tags(conn: sqlite3.Connection, problem_id: int) -> list[str]:
    if problem_id <= 0:
        return []
    row = conn.execute(
        "SELECT tags FROM problems WHERE problem_id = ?", (problem_id,)
    ).fetchone()
    if not row or not row["tags"]:
        return []
    raw = row["tags"]
    try:
        val = json.loads(raw) if isinstance(raw, str) else raw
        if isinstance(val, list):
            return [str(x) for x in val if str(x).strip()]
    except Exception:  # noqa: BLE001
        return []
    return []


def run_side_skill(
    conn: sqlite3.Connection,
    kind: str,
    *,
    user_profile: dict[str, Any],
    provider: str,
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
    problem_id: int = 0,
) -> tuple[str, bool]:
    """返回 (reply, from_cache)。"""
    from langchain_core.messages import HumanMessage

    kind = str(kind or "").strip()
    if kind not in SIDE_ACTIONS:
        raise ValueError(f"未知旁路技能: {kind}")

    day = str(china_today())
    profile = user_profile or {}
    weak = list(profile.get("weak_tags") or [])
    is_api = provider == "api"

    if kind == "daily_review":
        facts = assemble_daily_facts(profile)
        fp = _fingerprint(
            {
                "attempts": facts.get("attempts"),
                "accepted": facts.get("accepted"),
                "wrong": facts.get("wrong"),
                "rate": facts.get("acceptance_rate"),
                "slow": facts.get("slowest_tag"),
                "weak": facts.get("weak_tags"),
                "due": facts.get("review_due_count"),
                "problems": [
                    (p.get("problem_id"), p.get("attempts"), p.get("accepted_today"))
                    for p in list(facts.get("problems") or [])[:12]
                ],
                "h100": (facts.get("hot100_progress") or {}).get("done"),
            }
        )
        cached = _get_cache(conn, kind=kind, day=day, fingerprint=fp)
        if cached:
            return cached, True
        local_text = format_daily_review_local(facts)
        reply = local_text
        if is_api:
            try:
                reply, _ = stream_model_reply(
                    outbound=[HumanMessage(content=daily_review_api_prompt(facts))],
                    cancel_event=cancel_event,
                    session_id=session_id,
                    thread_id=thread_id,
                    meta={"node": "daily_review", "graph": "api", "side": True},
                )
            except GenerationCancelled:
                raise
            except Exception:  # noqa: BLE001
                reply = local_text
        _put_cache(conn, kind=kind, day=day, fingerprint=fp, reply=reply)
        return reply, False

    if kind == "review":
        candidates = pick_review_queue(conn, limit=3, prefer_tags=weak[:2])
        fp = _fingerprint(
            {
                "ids": [c.get("problem_id") for c in candidates],
                "due": int(profile.get("review_due_count") or 0),
            }
        )
        cached = _get_cache(conn, kind=kind, day=day, fingerprint=fp)
        if cached:
            return cached, True
        fallback = format_review_queue(candidates)
        reply = fallback
        try:
            prompt = polish_review_prompt(
                candidates, str(profile.get("summary_text") or "")
            )
            reply, _ = stream_model_reply(
                outbound=[HumanMessage(content=prompt)],
                cancel_event=cancel_event,
                session_id=session_id,
                thread_id=thread_id,
                meta={"node": "review", "graph": provider, "side": True},
            )
            if candidates and str(candidates[0].get("problem_id") or "") not in reply:
                reply = f"{fallback}\n\n{reply}"
        except GenerationCancelled:
            raise
        except Exception:  # noqa: BLE001
            reply = fallback
        _put_cache(conn, kind=kind, day=day, fingerprint=fp, reply=reply)
        return reply, False

    # recommend
    tags = _current_tags(conn, int(problem_id or 0))
    candidates = recommend_problems(
        conn,
        weak_tags=weak,
        limit=3,
        current_tags=tags,
        current_problem_id=int(problem_id or 0) or None,
    )
    fp = _fingerprint(
        {
            "ids": [c.get("problem_id") for c in candidates],
            "weak": weak[:5],
            "tags": tags[:5],
            "h100": (profile.get("hot100_progress") or {}).get("done"),
        }
    )
    cached = _get_cache(conn, kind=kind, day=day, fingerprint=fp)
    if cached:
        return cached, True
    fallback = format_recommendations_fallback(candidates)
    reply = fallback
    try:
        prompt = polish_prompt(candidates, str(profile.get("summary_text") or ""))
        reply, _ = stream_model_reply(
            outbound=[HumanMessage(content=prompt)],
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            meta={"node": "recommend", "graph": provider, "side": True},
        )
        if candidates and str(candidates[0]["problem_id"]) not in reply:
            reply = f"{fallback}\n\n{reply}"
    except GenerationCancelled:
        raise
    except Exception:  # noqa: BLE001
        reply = fallback
    _put_cache(conn, kind=kind, day=day, fingerprint=fp, reply=reply)
    return reply, False
