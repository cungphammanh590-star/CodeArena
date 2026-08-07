"""classify：意图 → phase → route（单一路由表，禁止连环覆盖）。"""

from __future__ import annotations

from typing import Any

from app.coach.intent_smart import (
    CONFIRM_CONFIDENCE_THRESHOLD,
    classify_smart_intent,
    injection_suspect,
    intent_to_phase,
    should_reclassify,
    wants_code_原文,
)
from app.coach.phases import coerce_phase, transition


def resolve_route(
    *,
    intent: str,
    phase: str,
    bound: bool,
    session_kind: str,
    action: str,
    user_text: str,
    prev_phase: str,
    confidence: float = 1.0,
    injection: bool = False,
) -> tuple[str, str, str]:
    """返回 (route, phase, close_scope)。

    route ∈ {refuse, offer, confirm, agent}
    close_scope ∈ {none, problem_segment, session}
    """
    close_scope = "none"
    act = str(action or "").strip()
    kind = str(session_kind or "lobby")
    new_phase = phase

    if act == "close":
        close_scope = "problem_segment"
        new_phase = "wrap"
        return "offer", new_phase, close_scope

    if act in {"diagnose", "deep_analysis"}:
        new_phase = "wrap" if act == "diagnose" else new_phase
        return "agent", new_phase, close_scope

    # 注入软信号 / 过低置信 → 向用户确认，不进 agent
    if injection:
        return "confirm", "lobby", close_scope

    if intent == "off_topic":
        return "refuse", "lobby", close_scope

    need_confirm = confidence < CONFIRM_CONFIDENCE_THRESHOLD or intent == "clarify"
    # 已绑题的短句题内帮助：允许略低于阈值直接进 agent
    bound_help_ok = (
        intent == "in_problem_help"
        and bound
        and confidence >= 0.7
        and not injection
    )
    if need_confirm and not bound_help_ok:
        return "confirm", new_phase if new_phase != "wrap" else "lobby", close_scope

    # 专题复盘 / 进度 → agent（带工具提示）
    if intent == "status_review" or (
        kind == "topic" and intent in {"clarify", "meta_product"} and "进度" in (user_text or "")
    ):
        return "agent", "today_brief", close_scope

    # 空闲选题：未绑题或 lobby/prep/wrap → offer（确定性 CTA，非模糊 confirm）
    if intent in {"practice_continue", "practice_new", "meta_product"} and (
        new_phase in {"lobby", "prep", "wrap"} or not bound
    ):
        return "offer", new_phase if new_phase != "wrap" else "lobby", close_scope

    # 题内说做完 → 收束本段并 offer
    if (
        intent in {"clarify", "meta_product"}
        and prev_phase == "in_problem"
        and "做完" in (user_text or "")
    ):
        return "offer", "wrap", "problem_segment"

    return "agent", new_phase, close_scope


def classify_turn(state: dict[str, Any]) -> dict[str, Any]:
    """从 state 计算 intent/phase/route/close_scope。"""
    msgs = list(state.get("messages") or [])
    user_text = ""
    for m in reversed(msgs):
        if "Human" in m.__class__.__name__:
            user_text = str(getattr(m, "content", "") or "")
            break

    turn = int(state.get("turn_count") or 0) + 1
    prev_phase = coerce_phase(state.get("phase"))
    bound_pid = int(state.get("problem_id") or 0)
    bound = bound_pid > 0
    action = str(state.get("pending_action") or "")
    session_kind = str(state.get("session_kind") or "lobby")
    topic = str(state.get("topic") or "")
    injection = injection_suspect(user_text)

    if should_reclassify(phase=prev_phase, turn_count=turn, user_text=user_text):
        intent, confidence = classify_smart_intent(
            user_text,
            bound_problem_id=bound_pid,
            action=action,
            topic=topic,
        )
    else:
        intent = str(state.get("intent") or "in_problem_help") or "in_problem_help"
        confidence = 0.85

    if injection and intent != "off_topic":
        # 不把注入句直接当题内帮助
        confidence = min(confidence, 0.4)

    target_phase = coerce_phase(
        intent_to_phase(intent, bound=bound, session_kind=session_kind),
        default=prev_phase,
    )
    new_phase = transition(prev_phase, target_phase)

    route, new_phase, close_scope = resolve_route(
        intent=intent,
        phase=new_phase,
        bound=bound,
        session_kind=session_kind,
        action=action,
        user_text=user_text,
        prev_phase=prev_phase,
        confidence=confidence,
        injection=injection,
    )

    return {
        "intent": intent,
        "phase": new_phase,
        "turn_count": turn,
        "allow_code_原文": wants_code_原文(user_text) and not injection,
        "route": route,
        "close_scope": close_scope,
        "problem_id": bound_pid,
        "intent_confidence": confidence,
        "injection_suspect": injection,
    }
