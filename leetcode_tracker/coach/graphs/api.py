"""ApiGraph：意图分流 + 优化/推荐/日回顾 + 诊断/精析 + 保守压缩。"""

from __future__ import annotations

import threading
from typing import Any

from leetcode_tracker.coach.graphs.common import (
    GenerationCancelled,
    append_unique,
    build_system_content,
    extract_identifiers,
    extract_negations,
    last_human_text,
    open_checkpoint_conn,
    stream_model_reply,
    update_vague_counter,
)
from leetcode_tracker.coach.graphs.skill_nodes import (
    prepare_intent_update,
    route_after_intent,
    run_daily_review_node,
    run_optimize_api,
    run_recommend_node,
)
from leetcode_tracker.coach.sessions import abandon_session
from leetcode_tracker.coach.state import (
    API_COMPRESS_AFTER_TURNS,
    CoachState,
)
from leetcode_tracker.infra.config import switch_to_ollama_keep_key
from leetcode_tracker.infra.db import init_db as _init_db_for_failover


_DIAGNOSE_EXTRA = """## 本轮任务：结束并诊断
请输出「代码审查式诊断报告」：指出 2–4 个具体缺陷或风险，引用真实标识符。
禁止给出完整可运行解法，禁止 markdown 代码块。以简短建议收尾。"""

_DEEP_EXTRA = """## 本轮任务：查看精析
用户已深度纠缠。可用文字步骤 + 伪代码级说明讲清标准思路。
仍禁止完整可运行语言代码与 ``` 代码块。"""


def _maybe_compress_messages(state: dict[str, Any]) -> dict[str, Any]:
    """超轮次时折叠更早消息为摘要，保留最近 2 轮人类/助手原文。"""
    from langchain_core.messages import AIMessage

    turn = int(state.get("turn_count") or 0)
    if turn < API_COMPRESS_AFTER_TURNS:
        return {}
    messages = list(state.get("messages") or [])
    if len(messages) <= 5:
        return {}
    keep = messages[-4:]
    old = messages[:-4]
    bits: list[str] = []
    for m in old:
        role = "用户" if "Human" in m.__class__.__name__ else "助手"
        bits.append(f"- {role}：{str(getattr(m, 'content', '') or '')[:120]}")
    prev = str(state.get("context_summary") or "").strip()
    summary = (prev + "\n" if prev else "") + "\n".join(bits[-12:])
    compressed = [
        AIMessage(content="（系统已压缩更早轮次，详见上下文摘要与否定清单。）"),
        *keep,
    ]
    return {"messages": compressed, "context_summary": summary.strip()[:2000]}


def compile_api_graph(
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
        return prepare_intent_update(state, provider="api")

    def route_turn(state: CoachState) -> str:
        return route_after_intent(state, provider="api")

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

    def _run_guided(
        state: CoachState,
        *,
        extra: str,
        node: str,
        force_deep: bool = False,
    ) -> dict[str, Any]:
        writer = get_stream_writer()
        compress_update = _maybe_compress_messages(state)
        working = {**state, **compress_update}
        messages = list(working.get("messages") or state.get("messages") or [])
        user_text = last_human_text(messages)
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
        system = SystemMessage(
            content=build_system_content(
                {**working, "rejected_suspicions": rejected}, extra=extra
            )
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
                    "node": node,
                    "graph": "api",
                    "fallback_turn": fallback_turn,
                    "submission_status": state.get("submission_status"),
                    "force_deep": force_deep,
                },
            )
            writer({"type": "token", "text": reply})
            out = {
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
                "pending_action": "",
                "exit_offered": True,
            }
            if compress_update.get("context_summary") is not None:
                out["context_summary"] = compress_update["context_summary"]
            return out
        except GenerationCancelled:
            raise
        except Exception as exc:  # noqa: BLE001
            return {
                "done": False,
                "fallback_turn_count": fallback_turn,
                "generation_error": str(exc),
                "provider_failover": True,
                "rejected_suspicions": rejected,
                "mentioned_identifiers": idents,
                "consecutive_vague": vague_n,
                "pending_action": "",
            }

    def coach_reply(state: CoachState) -> dict[str, Any]:
        return _run_guided(state, extra="", node="api_coach_reply")

    def diagnose(state: CoachState) -> dict[str, Any]:
        result = _run_guided(state, extra=_DIAGNOSE_EXTRA, node="diagnose")
        get_stream_writer()({"type": "diagnose", "source": "api"})
        result["done"] = True
        return result

    def deep_analysis(state: CoachState) -> dict[str, Any]:
        result = _run_guided(
            state, extra=_DEEP_EXTRA, node="deep_analysis", force_deep=True
        )
        get_stream_writer()({"type": "deep_analysis", "source": "api"})
        return result

    def recommend(state: CoachState) -> dict[str, Any]:
        return run_recommend_node(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            provider="api",
        )

    def daily_review(state: CoachState) -> dict[str, Any]:
        return run_daily_review_node(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
            provider="api",
        )

    def optimize(state: CoachState) -> dict[str, Any]:
        return run_optimize_api(
            state,
            cancel_event=cancel_event,
            session_id=session_id,
            thread_id=thread_id,
        )

    def route_after_reply(state: CoachState) -> str:
        if state.get("generation_error"):
            return "fallback_reply"
        return "__end__"

    def fallback_reply(state: CoachState) -> dict[str, Any]:
        """API 失败：切回 Ollama 配置并 abandon，禁止同 thread 换 LocalGraph 续聊。"""
        fallback_turn = int(state.get("fallback_turn_count") or 0)
        err = str(state.get("generation_error") or "unknown error")
        switch_to_ollama_keep_key()
        failover_conn = _init_db_for_failover()
        try:
            abandon_session(failover_conn, session_id)
        finally:
            failover_conn.close()
        reply = (
            "DeepSeek 暂时不可达"
            + (f"（{err}）" if err else "")
            + "。已将设置切回本地 Ollama（API Key 仍保留）。"
            "本对话已结束；请关闭本页后用本地模式重新打开陪练（不会在同一会话静默换图）。"
        )
        get_stream_writer()(
            {
                "type": "fallback",
                "text": reply,
                "message": "DeepSeek 不可达，已切回本地 Ollama；请重新打开陪练。",
                "reopen_required": True,
                "session_abandoned": True,
            }
        )
        return {
            "messages": [AIMessage(content=reply)],
            "done": True,
            "fallback_turn_count": fallback_turn + 1,
            "generation_error": "",
            "provider_failover": False,
        }

    route_map = {
        "coach_reply": "coach_reply",
        "close_session": "close_session",
        "diagnose": "diagnose",
        "deep_analysis": "deep_analysis",
        "recommend": "recommend",
        "daily_review": "daily_review",
        "optimize": "optimize",
        "answer_egress": "diagnose",
    }

    builder = StateGraph(CoachState)
    builder.add_node("prepare_intent", prepare_intent)
    builder.add_node("coach_reply", coach_reply)
    builder.add_node("close_session", close_session)
    builder.add_node("diagnose", diagnose)
    builder.add_node("deep_analysis", deep_analysis)
    builder.add_node("recommend", recommend)
    builder.add_node("daily_review", daily_review)
    builder.add_node("optimize", optimize)
    builder.add_node("fallback_reply", fallback_reply)
    builder.add_edge(START, "prepare_intent")
    builder.add_conditional_edges("prepare_intent", route_turn, route_map)
    for node in ("coach_reply", "diagnose", "deep_analysis", "optimize"):
        builder.add_conditional_edges(
            node,
            route_after_reply,
            {"fallback_reply": "fallback_reply", "__end__": END},
        )
    builder.add_edge("recommend", END)
    builder.add_edge("daily_review", END)
    builder.add_edge("fallback_reply", END)
    builder.add_edge("close_session", END)

    checkpoint_conn = open_checkpoint_conn()
    try:
        graph = builder.compile(checkpointer=SqliteSaver(checkpoint_conn))
        graph._leetcode_checkpoint_conn = checkpoint_conn  # type: ignore[attr-defined]
        return graph
    except Exception:
        checkpoint_conn.close()
        raise
