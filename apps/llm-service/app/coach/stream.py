"""智能教练 SSE：LangGraph 阶段图驱动。"""

from __future__ import annotations

import logging
import threading
from collections.abc import Iterator
from typing import Any, Optional

from app.coach.checkpoint import get_checkpoint_backend, thread_id_for
from app.coach.graph import GenerationCancelled, _action_prompt, compile_smart_graph
from app.coach.phases import coerce_phase
from app.services.llm_provider import fetch_user_llm_settings
from app.services.tool_client import JavaToolClient

logger = logging.getLogger(__name__)


def chat_stream(
    session: dict[str, Any],
    message: str,
    *,
    action: str = "",
    cancel_event: Optional[threading.Event] = None,
) -> Iterator[dict[str, Any]]:
    """Smart LangGraph 单回合；事件含 ready/token/info/done/error。

    L2（Postgres）落库在图内 persist 节点完成，不在 SSE 收尾图外回调。
    """
    from langchain_core.messages import AIMessage, HumanMessage

    stop = cancel_event or threading.Event()
    session_id = str(session["session_id"])
    user_public_id = str(session.get("user_public_id") or "")
    cache_key = thread_id_for(user_public_id=user_public_id, session_id=session_id)

    try:
        llm = fetch_user_llm_settings(user_public_id=user_public_id)
    except Exception as exc:  # noqa: BLE001
        yield {
            "type": "error",
            "message": f"无法读取用户 LLM 配置：{exc}",
        }
        return

    provider = str(llm.get("provider") or "").lower()
    if provider == "api" and not llm.get("api_key"):
        yield {
            "type": "error",
            "message": "智能教练需要云端 API Key。请到维护台为当前用户配置后重试。",
        }
        return
    if provider not in {"api", "ollama"}:
        yield {
            "type": "error",
            "message": f"不支持的 provider={provider or 'empty'}。请在维护台选择 Ollama 或 API。",
        }
        return

    user_text = str(message or "").strip()
    extra = _action_prompt(action)
    if extra:
        user_text = f"{user_text}\n\n（系统指令）{extra}".strip() if user_text else extra
    if not user_text:
        yield {"type": "error", "message": "message 或 action 必填其一"}
        return

    yield {
        "type": "ready",
        "session_id": session_id,
        "graph": "smart",
        "actions_hint": ["diagnose", "deep_analysis", "close"],
        "user_public_id": user_public_id,
        "checkpoint": get_checkpoint_backend(),
    }

    tools = JavaToolClient()

    try:
        graph = compile_smart_graph(stop, tools=tools)
        config = {"configurable": {"thread_id": cache_key}}
        snapshot = graph.get_state(config)
        values = (snapshot.values if snapshot else {}) or {}
        prior = list(values.get("messages") or [])
        phase = coerce_phase(values.get("phase"))
        turn_count = int(values.get("turn_count") or 0)
        refuse_short = bool(values.get("refuse_short"))

        messages: list[Any] = list(prior)
        if not messages and session.get("opening"):
            messages.append(AIMessage(content=str(session.get("opening") or "")))
        messages.append(HumanMessage(content=user_text))

        bound_pid = int(
            values.get("problem_id") or session.get("problem_id") or 0
        )
        topic = str(values.get("topic") or session.get("topic") or "")
        session_kind = str(
            values.get("session_kind")
            or session.get("session_kind")
            or ("topic" if topic else ("problem" if bound_pid > 0 else "lobby"))
        )
        # 不在图外凭空把 lobby 升为 in_problem；由 hydrate/L2 phase 决定

        graph_input = {
            "messages": messages,
            "session_id": session_id,
            "user_public_id": user_public_id,
            "session_kind": session_kind,
            "topic": topic,
            "phase": phase,
            "intent": str(values.get("intent") or ""),
            "turn_count": turn_count,
            "summary": str(values.get("summary") or session.get("summary") or ""),
            "pending_action": action,
            "problem_id": bound_pid,
            "allow_code_原文": False,
            "route": "",
            "reply": "",
            "offer_cta": "",
            "close_scope": "none",
            "refuse_short": refuse_short,
            "pending_tool_rounds": 0,
            "force_digest": False,
            "tokens_emitted": False,
            "intent_confidence": 0.0,
            "injection_suspect": False,
            "confirm_choices": [],
            "profile_digest": {},
            "memory_digest": [],
            "topic_digest": {},
            "offer_payload": {},
        }

        reply = ""
        close_scope = "none"
        intent_out = str(values.get("intent") or "")
        for mode, data in graph.stream(
            graph_input,
            config,
            stream_mode=["custom", "updates"],
        ):
            if stop.is_set():
                raise GenerationCancelled()
            if mode == "custom" and isinstance(data, dict):
                event = dict(data)
                if event.get("type") == "token":
                    piece = str(event.get("text") or "")
                    if event.get("replace"):
                        reply = piece
                    else:
                        reply += piece
                if event.get("type") == "info" and event.get("intent"):
                    intent_out = str(event.get("intent") or intent_out)
                yield event
            elif mode == "updates" and isinstance(data, dict):
                for _node, update in data.items():
                    if isinstance(update, dict):
                        if update.get("reply"):
                            reply = str(update.get("reply") or reply)
                        if update.get("intent"):
                            intent_out = str(update.get("intent") or intent_out)
                        if update.get("close_scope"):
                            close_scope = str(update.get("close_scope") or close_scope)

        final = graph.get_state(config)
        phase_out = phase
        problem_out = bound_pid
        if final and final.values:
            reply = str(final.values.get("reply") or reply)
            phase_out = coerce_phase(final.values.get("phase") or phase)
            close_scope = str(final.values.get("close_scope") or close_scope)
            problem_out = int(final.values.get("problem_id") or problem_out or 0)
            if final.values.get("intent"):
                intent_out = str(final.values.get("intent") or intent_out)
            if final.values.get("topic"):
                session["topic"] = str(final.values["topic"])
            if final.values.get("session_kind"):
                session["session_kind"] = str(final.values["session_kind"])
            session["problem_id"] = problem_out

        done = close_scope == "session"
        yield {
            "type": "done",
            "done": done,
            "close_scope": close_scope,
            "reply": reply,
            "graph": "smart",
            "phase": phase_out,
        }
    except GenerationCancelled:
        return
    except Exception as exc:  # noqa: BLE001
        logger.exception("coach stream failed")
        yield {"type": "error", "message": str(exc)}
