"""陪练会话服务：模板即时启动，按 provider 分流 LocalGraph / ApiGraph。"""

from __future__ import annotations

import sqlite3
import threading
from collections.abc import Iterator
from typing import Any, Optional

from leetcode_tracker.coach.context import build_coach_context
from leetcode_tracker.coach.graphs import graph_for_provider
from leetcode_tracker.coach.graphs.common import GenerationCancelled
from leetcode_tracker.coach.opening import template_opening
from leetcode_tracker.coach.profile import build_user_profile
from leetcode_tracker.coach.sessions import (
    get_or_create_session,
    get_session,
    is_session_abandoned,
    touch_session,
)
from leetcode_tracker.coach.state import ACTIONS
from leetcode_tracker.core.submissions import get_submission_by_id
from leetcode_tracker.infra.timeutil import china_today
from leetcode_tracker.llm.provider import get_llm_settings

_SESSION_LOCKS: dict[str, threading.Lock] = {}
_SESSION_LOCKS_GUARD = threading.Lock()

PROFILE_MODES = frozenset({"daily_review", "recommend", "review"})


def _session_lock(session_id: str) -> threading.Lock:
    with _SESSION_LOCKS_GUARD:
        return _SESSION_LOCKS.setdefault(session_id, threading.Lock())


def try_acquire_session(session_id: str) -> bool:
    return _session_lock(session_id).acquire(blocking=False)


def release_session(session_id: str) -> None:
    lock = _session_lock(session_id)
    if lock.locked():
        lock.release()


def _session_payload(
    session: dict[str, Any],
    *,
    opening_source: str,
    reused: bool,
    requested_submission_id: str,
    resolved_submission_id: str,
    fallback_used: bool,
    context_preview: str = "",
    mode: str = "",
) -> dict[str, Any]:
    return {
        "session_id": session["session_id"],
        "opening": session["opening"],
        "problem_id": session["problem_id"],
        "submission_id": session["submission_id"],
        "submission_status": session.get("submission_status") or "",
        "requested_submission_id": requested_submission_id,
        "resolved_submission_id": resolved_submission_id,
        "fallback_used": fallback_used,
        "opening_source": opening_source,
        "reused": reused,
        "context_preview": context_preview
        or str(session.get("context_markdown") or "")[:400],
        "graph": get_llm_settings().get("provider") or "ollama",
        "mode": mode or "",
    }


def _prepare_profile_mode(conn: sqlite3.Connection, mode: str) -> dict[str, Any]:
    """无 submission 的日回顾 / 推荐会话。"""
    from leetcode_tracker.coach.daily_review import format_daily_review_local
    from leetcode_tracker.coach.daily_review import assemble_daily_facts

    day = china_today().isoformat()
    profile = build_user_profile(conn)
    if mode == "daily_review":
        synthetic_id = f"mode:daily_review:{day}"
        opening = (
            f"今天是 {day}。我可以根据你今日入库的提交做事实回顾。"
            "点发送或再说一句「今日总结」即可；也可以问薄弱点。"
        )
        context = format_daily_review_local(assemble_daily_facts(profile))
        status = "DailyReview"
        problem_id = 0
        thread_hint = synthetic_id
    elif mode == "review":
        synthetic_id = f"mode:review:{day}"
        opening = (
            "这是「今日复习」：只给你到期的已 AC 旧题（固定间隔）。"
            "点发送开始拉复习队列；练新题请用「推荐下一题」。"
        )
        due_n = int(profile.get("review_due_count") or 0)
        context = (
            f"## 复习会话\n- 日期：{day}\n- 到期题数：{due_n}\n"
            f"- 画像：{profile.get('summary_text')}\n"
        )
        status = "Review"
        problem_id = 0
        thread_hint = synthetic_id
    else:
        synthetic_id = f"mode:recommend:{day}"
        opening = (
            "这是「推荐下一题」：只推 Hot100 未 AC 新题。"
            "点发送或说「推荐下一题」开始；温习旧题请用「今日复习」。"
        )
        weak = "、".join(profile.get("weak_tags") or []) or "（暂无）"
        h100 = profile.get("hot100_progress") or {}
        context = (
            f"## 推荐会话\n- 日期：{day}\n- Hot100：{h100.get('done', 0)}/"
            f"{h100.get('total', 0)}\n- 薄弱标签：{weak}\n"
        )
        status = "Recommend"
        problem_id = 0
        thread_hint = synthetic_id

    session, created = get_or_create_session(
        conn,
        submission_id=synthetic_id,
        problem_id=problem_id,
        opening=opening,
        context_markdown=context,
        submission_status=status,
    )
    # thread_id 保持 session_id；synthetic submission_id 保证日级幂等
    _ = thread_hint
    return _session_payload(
        session,
        opening_source="template" if created else "cached",
        reused=not created,
        requested_submission_id="",
        resolved_submission_id=synthetic_id,
        fallback_used=False,
        context_preview=context[:400],
        mode=mode,
    )


def prepare(
    conn: sqlite3.Connection,
    submission_id: str = "",
    *,
    problem_id: Optional[int] = None,
    reuse_existing: bool = True,  # noqa: ARG001
    mode: str = "",
) -> dict[str, Any]:
    """只读提交事实并原子创建模板会话；绝不调用 LLM。"""
    mode = str(mode or "").strip()
    if mode in PROFILE_MODES:
        return _prepare_profile_mode(conn, mode)

    ctx = build_coach_context(conn, submission_id, problem_id=problem_id)
    status = str(ctx["status"])
    opening = template_opening(
        problem_id=int(ctx["problem_id"]),
        title=str(ctx["title"]),
        status=status,
        placement=ctx.get("placement"),
        today_count=int(ctx["today_count"]),
    )
    session, created = get_or_create_session(
        conn,
        submission_id=str(ctx["resolved_submission_id"]),
        problem_id=int(ctx["problem_id"]),
        opening=opening,
        context_markdown=str(ctx["markdown"]),
        submission_status=status,
    )
    return _session_payload(
        session,
        opening_source="template" if created else "cached",
        reused=not created,
        requested_submission_id=str(ctx["requested_submission_id"]),
        resolved_submission_id=str(ctx["resolved_submission_id"]),
        fallback_used=bool(ctx["fallback_used"]),
        context_preview=str(ctx["markdown"])[:400],
    )


def chat(
    conn: sqlite3.Connection, session_id: str, message: str, *, action: str = ""
) -> dict[str, Any]:
    chunks: list[str] = []
    done = False
    for event in chat_stream(conn, session_id, message, action=action):
        if event.get("type") in {
            "token",
            "fallback",
            "answer_egress",
            "diagnose",
            "deep_analysis",
        }:
            chunks.append(str(event.get("text") or ""))
        elif event.get("type") == "done":
            done = bool(event.get("done"))
            if event.get("reply") and not chunks:
                chunks.append(str(event["reply"]))
        elif event.get("type") == "error":
            raise RuntimeError(str(event.get("message") or "chat failed"))
    return {"reply": "".join(chunks), "done": done}


def _snapshot_value(snapshot: Any, key: str, default: Any) -> Any:
    if not snapshot or not snapshot.values:
        return default
    val = snapshot.values.get(key)
    return default if val is None else val


def _load_current_code(conn: sqlite3.Connection, session: dict[str, Any]) -> str:
    sid = str(session.get("submission_id") or "")
    if sid.startswith("mode:"):
        return ""
    sub = get_submission_by_id(conn, sid) if sid else None
    if sub and sub.get("code"):
        return str(sub["code"])
    return ""


def chat_stream(
    conn: sqlite3.Connection,
    session_id: str,
    message: str,
    *,
    action: str = "",
    cancel_event: Optional[threading.Event] = None,
    lock_acquired: bool = False,
) -> Iterator[dict[str, Any]]:
    """由 LocalGraph / ApiGraph 执行单回合；事件含 ready/token/offer_exit/done/…"""
    from langchain_core.messages import AIMessage, HumanMessage

    action = str(action or "").strip()
    if action and action not in ACTIONS:
        yield {"type": "error", "message": f"未知 action: {action}"}
        return
    message = str(message or "").strip()
    if not message and not action:
        yield {"type": "error", "message": "message 或 action 必填其一"}
        return
    if not message and action:
        message = {
            "close": "结束",
            "show_skeleton": "看思路",
            "diagnose": "结束并诊断",
            "deep_analysis": "查看精析",
            "daily_review": "今日总结",
            "recommend": "推荐下一题",
            "review": "今日复习",
            "optimize": "帮我优化",
        }.get(action, action)

    session = get_session(conn, session_id)
    if session is None:
        yield {"type": "error", "message": f"未找到会话: {session_id}"}
        return
    if is_session_abandoned(session):
        yield {
            "type": "error",
            "code": "session_abandoned",
            "message": "本对话已结束。请重新打开陪练再继续。",
            "reopen_required": True,
        }
        return

    owns_lock = not lock_acquired
    if owns_lock and not try_acquire_session(session_id):
        yield {
            "type": "error",
            "code": "session_busy",
            "message": "该会话正在处理上一条消息",
        }
        return

    stop = cancel_event or threading.Event()
    reply_parts: list[str] = []
    done = False
    provider = str(get_llm_settings().get("provider") or "ollama")
    try:
        yield {
            "type": "ready",
            "session_id": session_id,
            "graph": "api" if provider == "api" else "local",
            "actions_hint": (
                [
                    "close",
                    "diagnose",
                    "deep_analysis",
                    "recommend",
                    "review",
                    "daily_review",
                ]
                if provider == "api"
                else ["close", "show_skeleton", "recommend", "review", "daily_review"]
            ),
        }
        thread_id = str(session["thread_id"])
        user_profile = build_user_profile(conn)
        current_code = _load_current_code(conn, session)
        with graph_for_provider(
            stop, session_id=session_id, thread_id=thread_id, provider=provider
        ) as graph:
            config = {"configurable": {"thread_id": thread_id}}
            snapshot = graph.get_state(config)
            has_messages = bool(
                snapshot and snapshot.values and snapshot.values.get("messages")
            )
            messages: list[Any] = [HumanMessage(content=message)]
            if not has_messages:
                messages.insert(0, AIMessage(content=str(session["opening"])))
            graph_input = {
                "messages": messages,
                "context_markdown": str(session.get("context_markdown") or ""),
                "submission_status": str(session.get("submission_status") or ""),
                "done": bool(_snapshot_value(snapshot, "done", False)),
                "fallback_turn_count": int(
                    _snapshot_value(snapshot, "fallback_turn_count", 0) or 0
                ),
                "generation_error": "",
                "provider_failover": False,
                "turn_count": int(_snapshot_value(snapshot, "turn_count", 0) or 0),
                "rejected_suspicions": list(
                    _snapshot_value(snapshot, "rejected_suspicions", []) or []
                ),
                "mentioned_identifiers": list(
                    _snapshot_value(snapshot, "mentioned_identifiers", []) or []
                ),
                "exit_offered": bool(_snapshot_value(snapshot, "exit_offered", False)),
                "degraded": bool(_snapshot_value(snapshot, "degraded", False)),
                "pending_action": action,
                "problem_id": int(session.get("problem_id") or 0),
                "last_assistant_text": str(
                    _snapshot_value(snapshot, "last_assistant_text", "") or ""
                ),
                "guardrail_stripped": bool(
                    _snapshot_value(snapshot, "guardrail_stripped", False)
                ),
                "consecutive_vague": int(
                    _snapshot_value(snapshot, "consecutive_vague", 0) or 0
                ),
                "context_summary": str(
                    _snapshot_value(snapshot, "context_summary", "") or ""
                ),
                "user_profile": user_profile,
                "current_code": current_code,
                "intent": str(_snapshot_value(snapshot, "intent", "") or ""),
                "analysis_result": str(
                    _snapshot_value(snapshot, "analysis_result", "") or ""
                ),
                "candidate_recommendations": list(
                    _snapshot_value(snapshot, "candidate_recommendations", []) or []
                ),
            }
            for mode, data in graph.stream(
                graph_input,
                config,
                stream_mode=["custom", "updates"],
            ):
                if stop.is_set():
                    raise GenerationCancelled()
                if mode != "custom" or not isinstance(data, dict):
                    continue
                event = dict(data)
                if event.get("type") in {
                    "token",
                    "fallback",
                    "answer_egress",
                    "diagnose",
                    "deep_analysis",
                }:
                    reply_parts.append(str(event.get("text") or ""))
                yield event
            final_snapshot = graph.get_state(config)
            done = bool(
                final_snapshot.values.get("done")
                if final_snapshot and final_snapshot.values
                else False
            )
        touch_session(conn, session_id)
        yield {
            "type": "done",
            "done": done,
            "reply": "".join(reply_parts),
            "graph": "api" if provider == "api" else "local",
        }
    except GenerationCancelled:
        return
    except Exception as exc:  # noqa: BLE001
        yield {"type": "error", "message": str(exc)}
    finally:
        if owns_lock:
            release_session(session_id)
