"""智能教练 SSE：LangGraph 阶段图驱动。"""

from __future__ import annotations

import logging
import threading
from collections.abc import Iterator
from typing import Any, Optional

from app.coach.checkpoint import (
    force_memory_checkpointer,
    get_checkpoint_backend,
    is_checkpoint_connectivity_error,
    thread_id_for,
)
from app.coach.graph import GenerationCancelled, _action_prompt, compile_smart_graph
from app.coach.phases import coerce_phase
from app.observability.langfuse_setup import flush_langfuse, get_langfuse_handler
from app.observability.usage_recorder import (
    begin_usage_turn,
    flush_usage_to_business,
    make_usage_callback,
)
from app.observability.logging_setup import log_extra
from app.observability.request_context import get_request_id
from app.services.llm_provider import fetch_user_llm_settings
from app.services.tool_client import JavaToolClient

logger = logging.getLogger(__name__)


def chat_stream(
    session: dict[str, Any],
    message: str,
    *,
    action: str = "",
    answers: Optional[list[dict[str, Any]]] = None,
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
    answer_list = [a for a in (answers or []) if isinstance(a, dict)]

    try:
        llm = fetch_user_llm_settings(user_public_id=user_public_id)
    except Exception as exc:  # noqa: BLE001
        msg = str(exc).strip() or "暂时读不到你的模型配置，请稍后再试"
        yield {"type": "error", "message": msg}
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
            "message": "当前模型配置不可用。请到维护台选择本地 Ollama 或云端 API。",
        }
        return

    user_text = str(message or "").strip()
    extra = _action_prompt(action)
    if extra:
        user_text = f"{user_text}\n\n（系统指令）{extra}".strip() if user_text else extra
    if action == "submit_user_reply":
        if not answer_list:
            yield {"type": "error", "message": "请先回答问题后再继续"}
            return
        if not user_text:
            bits = [
                f"{a.get('question_id')}: {a.get('text')}"
                for a in answer_list
                if a.get("text")
            ]
            user_text = "【澄清回答】" + ("；".join(bits) if bits else "(submitted)")
    elif not user_text:
        yield {"type": "error", "message": "请输入内容后再发送"}
        return

    yield {
        "type": "ready",
        "session_id": session_id,
        "graph": "smart",
        "actions_hint": ["diagnose", "deep_analysis", "close", "show_skeleton"],
        "user_public_id": user_public_id,
        "checkpoint": get_checkpoint_backend(),
    }

    tools = JavaToolClient()
    checkpoint_retried = False
    logger.info(
        "coach stream start session=%s action=%s",
        session_id,
        action or "-",
        extra=log_extra(request_id=get_request_id(), session_id=session_id),
    )

    while True:
        try:
            graph = compile_smart_graph(stop, tools=tools)
            config: dict[str, Any] = {"configurable": {"thread_id": cache_key}}
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
                "solve_session": values.get("solve_session"),
                "paused_ask": values.get("paused_ask"),
                "solve_progress_event": None,
                "code_run_last": values.get("code_run_last"),
                "awaiting_ask_user": False,
                "user_answers": answer_list,
                "resume_from_ask": False,
            }

            reply = ""
            close_scope = "none"
            intent_out = str(values.get("intent") or "")
            awaiting = ""
            stream_config: dict[str, Any] = {
                "configurable": {"thread_id": cache_key},
            }
            callbacks: list[Any] = []
            lf_handler = get_langfuse_handler()
            if lf_handler is not None:
                callbacks.append(lf_handler)
                stream_config["metadata"] = {
                    "langfuse_session_id": session_id,
                    "langfuse_user_id": user_public_id or "anon",
                }
            usage_cb = make_usage_callback()
            if usage_cb is not None:
                callbacks.append(usage_cb)
            if callbacks:
                stream_config["callbacks"] = callbacks
            begin_usage_turn()
            for mode, data in graph.stream(
                graph_input,
                stream_config,
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
                    if event.get("type") == "ask_user":
                        awaiting = "ask_user"
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
                            if update.get("paused_ask"):
                                awaiting = "ask_user"

            final = graph.get_state({"configurable": {"thread_id": cache_key}})
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
                if final.values.get("paused_ask"):
                    awaiting = "ask_user"

            done = close_scope == "session"
            done_event: dict[str, Any] = {
                "type": "done",
                "done": done,
                "close_scope": close_scope,
                "reply": reply,
                "graph": "smart",
                "phase": phase_out,
            }
            if awaiting and not done:
                done_event["awaiting"] = awaiting
                done_event["done"] = False
            flush_langfuse()
            flush_usage_to_business(
                user_public_id=user_public_id,
                session_id=session_id,
                provider=str(llm.get("provider") or ""),
                api_provider=str(llm.get("api_provider") or ""),
                model=str(llm.get("coach_model") or ""),
            )
            yield done_event
            return
        except GenerationCancelled:
            flush_langfuse()
            flush_usage_to_business(
                user_public_id=user_public_id,
                session_id=session_id,
                provider=str(llm.get("provider") or ""),
                api_provider=str(llm.get("api_provider") or ""),
                model=str(llm.get("coach_model") or ""),
            )
            return
        except Exception as exc:  # noqa: BLE001
            if not checkpoint_retried and is_checkpoint_connectivity_error(exc):
                logger.warning(
                    "checkpoint redis unavailable; fallback memory and retry once: %s",
                    exc,
                )
                force_memory_checkpointer("runtime redis down")
                checkpoint_retried = True
                continue
            logger.exception("coach stream failed")
            flush_langfuse()
            flush_usage_to_business(
                user_public_id=user_public_id,
                session_id=session_id,
                provider=str(llm.get("provider") or ""),
                api_provider=str(llm.get("api_provider") or ""),
                model=str(llm.get("coach_model") or ""),
            )
            # 不对用户暴露堆栈/内部异常原文
            yield {
                "type": "error",
                "message": "对话出了点问题，请稍后再试",
            }
            return
