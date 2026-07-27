"""LocalGraph：规则意图 + 浅层优化 + 推荐/日回顾 + offer_exit。"""

from __future__ import annotations

import threading
from typing import Any

from leetcode_tracker.coach.answer_egress import build_answer_egress
from leetcode_tracker.coach.exit_detect import is_repetitive_reply, should_offer_exit
from leetcode_tracker.coach.graphs.common import (
    GenerationCancelled,
    append_unique,
    build_system_content,
    extract_identifiers,
    extract_negations,
    fallback_local_text,
    last_human_text,
    open_checkpoint_conn,
    stream_model_reply,
    trim_messages_for_local,
    update_vague_counter,
)
from leetcode_tracker.coach.graphs.skill_nodes import (
    prepare_intent_update,
    route_after_intent,
    run_daily_review_node,
    run_optimize_local,
    run_recommend_node,
)
from leetcode_tracker.coach.state import CoachState
from leetcode_tracker.infra.db import init_db


def compile_local_graph(
    cancel_event: threading.Event,
    *,
    session_id: str,
    thread_id: str,
):
    from langchain_core.messages import AIMessage, SystemMessage
    from langgraph.checkpoint.sqlite import SqliteSaver
    from langgraph.config import get_stream_writer
    from langgraph.graph import END, START, StateGraph

    def prepare_intent(state: CoachState) -> dict[str, Any]:
        return prepare_intent_update(state, provider="ollama")

    def route_turn(state: CoachState) -> str:
        return route_after_intent(state, provider="ollama")

    def close_session(_state: CoachState) -> dict[str, Any]:
        summary = (
            "好的，今天先到这里。记得把刚才怀疑的点记下来，"
            "下次提交前再对一遍。"
        )
        get_stream_writer()({"type": "token", "text": summary})
        return {
            "messages": [AIMessage(content=summary)],
            "done": True,
            "pending_action": "",
        }

    def offer_exit(state: CoachState) -> dict[str, Any]:
        _offer, reason = should_offer_exit(state)
        writer = get_stream_writer()
        note = {
            "hard_turn_limit": "这题已经聊了不少轮。可以结束，或点「推荐下一题」换题巩固。",
            "vague_loop": "对话有点空转了。要结束、看思路，还是去推荐下一题？",
            "guardrail": "模型回复不稳定。建议结束、看思路，或推荐下一题。",
            "degraded": "模型表现下降。建议结束、看思路，或推荐下一题。",
            "repeat": "出现重复引导。建议结束、看思路，或推荐下一题。",
        }.get(reason, "可以结束本轮；需要换题请点「推荐下一题」（不会自动推荐）。")
        writer(
            {
                "type": "offer_exit",
                "reason": reason or "manual",
                "message": note,
                "actions": ["close", "show_skeleton", "recommend"],
                "auto_end": False,
            }
        )
        tip = f"（系统）{note}"
        writer({"type": "token", "text": tip})
        return {
            "messages": [AIMessage(content=tip)],
            "exit_offered": True,
            "degraded": True,
            "done": False,
            "pending_action": "",
        }

    def answer_egress(state: CoachState) -> dict[str, Any]:
        writer = get_stream_writer()
        pid = int(state.get("problem_id") or 0)
        conn = init_db()
        try:
            payload = build_answer_egress(
                conn, pid, degraded=bool(state.get("degraded"))
            )
        finally:
            conn.close()
        text = str(payload["text"])
        writer(
            {
                "type": "answer_egress",
                "text": text,
                "source": payload.get("source"),
            }
        )
        writer({"type": "token", "text": text})
        return {
            "messages": [AIMessage(content=text)],
            "pending_action": "",
            "done": False,
            "exit_offered": True,
        }

    def recommend(state: CoachState) -> dict[str, Any]:
        return run_recommend_node(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            provider="local",
        )

    def daily_review(state: CoachState) -> dict[str, Any]:
        return run_daily_review_node(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            provider="local",
        )

    def optimize(state: CoachState) -> dict[str, Any]:
        return run_optimize_local(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
        )

    def coach_reply(state: CoachState) -> dict[str, Any]:
        writer = get_stream_writer()
        messages = trim_messages_for_local(list(state.get("messages") or []))
        user_text = last_human_text(list(state.get("messages") or []))
        last_asst = str(state.get("last_assistant_text") or "")
        rejected = append_unique(
            list(state.get("rejected_suspicions") or []),
            extract_negations(user_text, last_asst),
        )
        idents = append_unique(
            list(state.get("mentioned_identifiers") or []),
            extract_identifiers(user_text + "\n" + last_asst),
        )
        vague_n = update_vague_counter(state, user_text)
        working = {**state, "rejected_suspicions": rejected}
        system = SystemMessage(
            content=build_system_content(working, include_full_context=False)
        )
        outbound = [system, *messages]
        turn = int(state.get("turn_count") or 0)
        fallback_turn = int(state.get("fallback_turn_count") or 0)
        try:
            reply, stripped = stream_model_reply(
                outbound=outbound,
                cancel_event=cancel_event,
                session_id=session_id,
                thread_id=thread_id,
                meta={
                    "node": "local_coach_reply",
                    "graph": "local",
                    "fallback_turn": fallback_turn,
                    "submission_status": state.get("submission_status"),
                },
            )
            degraded = bool(state.get("degraded"))
            if is_repetitive_reply(last_asst, reply):
                degraded = True
            if stripped:
                degraded = True
            writer({"type": "token", "text": reply})
            return {
                "messages": [AIMessage(content=reply)],
                "done": False,
                "turn_count": turn + 1,
                "fallback_turn_count": fallback_turn,
                "generation_error": "",
                "provider_failover": False,
                "rejected_suspicions": rejected,
                "mentioned_identifiers": idents,
                "last_assistant_text": reply,
                "guardrail_stripped": stripped,
                "consecutive_vague": vague_n,
                "degraded": degraded,
                "pending_action": "",
            }
        except GenerationCancelled:
            raise
        except Exception as exc:  # noqa: BLE001
            return {
                "done": False,
                "fallback_turn_count": fallback_turn,
                "generation_error": str(exc),
                "provider_failover": False,
                "rejected_suspicions": rejected,
                "mentioned_identifiers": idents,
                "consecutive_vague": vague_n,
                "pending_action": "",
            }

    def route_after_reply(state: CoachState) -> str:
        if state.get("generation_error"):
            return "fallback_reply"
        if bool(state.get("exit_offered")):
            return "__end__"
        offer, _reason = should_offer_exit(state)
        if offer:
            return "offer_exit"
        if bool(state.get("degraded")) and int(state.get("turn_count") or 0) >= 2:
            return "offer_exit"
        return "__end__"

    def fallback_reply(state: CoachState) -> dict[str, Any]:
        fallback_turn = int(state.get("fallback_turn_count") or 0)
        err = str(state.get("generation_error") or "unknown error")
        reply = fallback_local_text(fallback_turn)
        get_stream_writer()(
            {
                "type": "fallback",
                "text": reply,
                "message": f"模型不可用，已切换本地降级陪练：{err}",
            }
        )
        return {
            "messages": [AIMessage(content=reply)],
            "done": False,
            "fallback_turn_count": fallback_turn + 1,
            "generation_error": "",
            "degraded": True,
            "turn_count": int(state.get("turn_count") or 0) + 1,
            "last_assistant_text": reply,
        }

    route_map = {
        "coach_reply": "coach_reply",
        "close_session": "close_session",
        "offer_exit": "offer_exit",
        "answer_egress": "answer_egress",
        "recommend": "recommend",
        "daily_review": "daily_review",
        "optimize": "optimize",
    }

    builder = StateGraph(CoachState)
    builder.add_node("prepare_intent", prepare_intent)
    builder.add_node("coach_reply", coach_reply)
    builder.add_node("close_session", close_session)
    builder.add_node("offer_exit", offer_exit)
    builder.add_node("answer_egress", answer_egress)
    builder.add_node("recommend", recommend)
    builder.add_node("daily_review", daily_review)
    builder.add_node("optimize", optimize)
    builder.add_node("fallback_reply", fallback_reply)
    builder.add_edge(START, "prepare_intent")
    builder.add_conditional_edges("prepare_intent", route_turn, route_map)
    builder.add_conditional_edges(
        "coach_reply",
        route_after_reply,
        {
            "fallback_reply": "fallback_reply",
            "offer_exit": "offer_exit",
            "__end__": END,
        },
    )
    builder.add_edge("fallback_reply", END)
    builder.add_edge("close_session", END)
    builder.add_edge("offer_exit", END)
    builder.add_edge("answer_egress", END)
    builder.add_edge("recommend", END)
    builder.add_edge("daily_review", END)
    builder.add_edge("optimize", END)

    checkpoint_conn = open_checkpoint_conn()
    try:
        graph = builder.compile(checkpointer=SqliteSaver(checkpoint_conn))
        graph._leetcode_checkpoint_conn = checkpoint_conn  # type: ignore[attr-defined]
        return graph
    except Exception:
        checkpoint_conn.close()
        raise
