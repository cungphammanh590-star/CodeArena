"""推荐 / 日回顾 / 优化 等共享节点逻辑（供 LocalGraph / ApiGraph 调用）。"""

from __future__ import annotations

import threading
from typing import Any

from leetcode_tracker.coach.answer_egress import get_curated_skeleton, get_latest_accepted
from leetcode_tracker.coach.daily_review import (
    assemble_daily_facts,
    daily_review_api_prompt,
    format_daily_review_local,
)
from leetcode_tracker.coach.graphs.common import (
    GenerationCancelled,
    build_system_content,
    is_done_message,
    last_human_text,
    stream_model_reply,
    trim_messages_for_local,
)
from leetcode_tracker.coach.intent import (
    classify_api_structured,
    classify_by_rules,
    classify_local_discriminative,
    resolve_route,
    sync_invoke_label,
)
from leetcode_tracker.coach.recommend import (
    format_recommendations_fallback,
    polish_prompt,
    recommend_problems,
)
from leetcode_tracker.coach.structure_diff import (
    compare_features,
    extract_code_features,
    format_structure_conclusions,
)
from leetcode_tracker.infra.db import init_db


def prepare_intent_update(state: dict[str, Any], *, provider: str) -> dict[str, Any]:
    """写入 intent；含 action 时映射意图且不调用分类模型。"""
    action = str(state.get("pending_action") or "").strip()
    if action:
        mapped = {
            "recommend": "recommend",
            "daily_review": "daily_review",
            "optimize": "optimize",
            "show_skeleton": "show_answer",
        }.get(action, str(state.get("intent") or "chat") or "chat")
        return {"intent": mapped}

    user_text = last_human_text(list(state.get("messages") or []))
    if is_done_message(user_text):
        return {"intent": "chat"}

    ruled = classify_by_rules(user_text)
    if ruled:
        return {"intent": ruled}

    if provider == "api":
        intent = classify_api_structured(user_text, invoke_llm=sync_invoke_label)
    else:
        intent = classify_local_discriminative(user_text, invoke_llm=sync_invoke_label)
    return {"intent": intent}


def route_after_intent(state: dict[str, Any], *, provider: str) -> str:
    from leetcode_tracker.coach.exit_detect import should_offer_exit

    action = str(state.get("pending_action") or "").strip()
    if action:
        return resolve_route(action=action, intent="", provider=provider)

    if bool(state.get("done")):
        return "close_session"
    user_text = last_human_text(list(state.get("messages") or []))
    if is_done_message(user_text):
        return "close_session"

    is_local = provider in {"ollama", "local"}
    if is_local:
        offer, _reason = should_offer_exit(state, user_message=user_text)
        if offer and not bool(state.get("exit_offered")):
            return "offer_exit"

    intent = str(state.get("intent") or "chat") or "chat"
    return resolve_route(action="", intent=intent, provider=provider)


def run_recommend_node(
    state: dict[str, Any],
    *,
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
    provider: str,
) -> dict[str, Any]:
    from langchain_core.messages import AIMessage, HumanMessage
    from langgraph.config import get_stream_writer

    writer = get_stream_writer()
    profile = state.get("user_profile") or {}
    weak = list(profile.get("weak_tags") or [])
    conn = init_db()
    try:
        candidates = recommend_problems(conn, weak_tags=weak, limit=3)
    finally:
        conn.close()

    fallback = format_recommendations_fallback(candidates)
    reply = fallback
    try:
        prompt = polish_prompt(candidates, str(profile.get("summary_text") or ""))
        reply, _stripped = stream_model_reply(
            outbound=[HumanMessage(content=prompt)],
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            meta={"node": "recommend", "graph": provider},
        )
        if candidates and str(candidates[0]["problem_id"]) not in reply:
            reply = f"{fallback}\n\n{reply}"
    except GenerationCancelled:
        raise
    except Exception:  # noqa: BLE001
        reply = fallback

    writer({"type": "token", "text": reply})
    return {
        "messages": [AIMessage(content=reply)],
        "candidate_recommendations": candidates,
        "intent": "recommend",
        "pending_action": "",
        "done": False,
        "turn_count": int(state.get("turn_count") or 0) + 1,
        "last_assistant_text": reply,
    }


def run_daily_review_node(
    state: dict[str, Any],
    *,
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
    provider: str,
) -> dict[str, Any]:
    from langchain_core.messages import AIMessage, HumanMessage
    from langgraph.config import get_stream_writer

    writer = get_stream_writer()
    facts = assemble_daily_facts(state.get("user_profile"))
    local_text = format_daily_review_local(facts)

    if provider == "api":
        try:
            reply, _ = stream_model_reply(
                outbound=[HumanMessage(content=daily_review_api_prompt(facts))],
                cancel_event=cancel_event,
                session_id=session_id,
                thread_id=thread_id,
                meta={"node": "daily_review", "graph": "api"},
            )
        except GenerationCancelled:
            raise
        except Exception:  # noqa: BLE001
            reply = local_text
    else:
        reply = local_text

    writer({"type": "token", "text": reply})
    return {
        "messages": [AIMessage(content=reply)],
        "intent": "daily_review",
        "pending_action": "",
        "done": False,
        "turn_count": int(state.get("turn_count") or 0) + 1,
        "last_assistant_text": reply,
    }


def run_optimize_local(
    state: dict[str, Any],
    *,
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
) -> dict[str, Any]:
    """浅层优化：结构特征对比 + 陷阱文案。

    红线：历史 AC 的 source_code 不得进入 system/user prompt（仅特征对比结论）。
    """
    from langchain_core.messages import AIMessage, SystemMessage
    from langgraph.config import get_stream_writer

    writer = get_stream_writer()
    pid = int(state.get("problem_id") or 0)
    user_code = str(state.get("current_code") or "")
    user_feat = extract_code_features(user_code, language="python")

    ref_feat = None
    trap = ""
    conn = init_db()
    try:
        ac = get_latest_accepted(conn, pid) if pid else None
        ac_source = str((ac or {}).get("code") or "")
        if ac_source.strip():
            # 只提取特征；ac_source 不得传入 build_system_content / outbound
            ref_feat = extract_code_features(
                ac_source, language=str((ac or {}).get("language") or "python")
            )
            del ac_source
        trap = get_curated_skeleton(pid) if pid else ""
    finally:
        conn.close()

    comparison = compare_features(user_feat, ref_feat)
    conclusions = format_structure_conclusions(comparison)
    trap_block = (
        f"## 常见陷阱提示\n{trap}"
        if trap
        else "## 常见陷阱提示\n（本题暂无 curated 文案）"
    )
    extra = (
        "## 本轮任务：浅层优化提示（本地）\n"
        "只根据「结构对比结论」与「常见陷阱」给方向，禁止输出完整可运行解法，"
        "禁止编造未见过的代码行。\n\n"
        f"{conclusions}\n\n{trap_block}"
    )
    system = SystemMessage(
        content=build_system_content(state, extra=extra, include_full_context=False)
    )
    messages = trim_messages_for_local(list(state.get("messages") or []))
    outbound = [system, *messages]

    turn = int(state.get("turn_count") or 0)
    try:
        reply, stripped = stream_model_reply(
            outbound=outbound,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            meta={"node": "optimize_local", "graph": "local"},
        )
    except GenerationCancelled:
        raise
    except Exception:  # noqa: BLE001
        reply = conclusions + "\n\n" + (trap or "先检查循环嵌套与数据结构选型。")
        stripped = False

    writer({"type": "token", "text": reply})
    return {
        "messages": [AIMessage(content=reply)],
        "intent": "optimize",
        "analysis_result": conclusions,
        "pending_action": "",
        "done": False,
        "turn_count": turn + 1,
        "last_assistant_text": reply,
        "guardrail_stripped": stripped,
    }


def run_optimize_api(
    state: dict[str, Any],
    *,
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
) -> dict[str, Any]:
    from langchain_core.messages import AIMessage, SystemMessage
    from langgraph.config import get_stream_writer

    writer = get_stream_writer()
    extra = (
        "## 本轮任务：优化分析\n"
        "对比题目复杂度要求与用户当前代码，指出瓶颈类型（循环嵌套 / 数据结构 / 冗余计算等）"
        "并给出优化方向。禁止输出完整可运行 AC 代码或 markdown 代码块中的完整解法。"
    )
    system = SystemMessage(content=build_system_content(state, extra=extra))
    outbound = [system, *list(state.get("messages") or [])]
    turn = int(state.get("turn_count") or 0)
    try:
        reply, stripped = stream_model_reply(
            outbound=outbound,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            meta={"node": "optimize_api", "graph": "api"},
        )
    except GenerationCancelled:
        raise
    except Exception as exc:  # noqa: BLE001
        return {
            "done": False,
            "generation_error": str(exc),
            "provider_failover": True,
            "pending_action": "",
            "intent": "optimize",
        }

    writer({"type": "token", "text": reply})
    return {
        "messages": [AIMessage(content=reply)],
        "intent": "optimize",
        "analysis_result": reply[:500],
        "pending_action": "",
        "done": False,
        "turn_count": turn + 1,
        "last_assistant_text": reply,
        "guardrail_stripped": stripped,
        "generation_error": "",
    }
