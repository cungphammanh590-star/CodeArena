"""SmartState：durable 会话进展 vs ephemeral 本回合工作区。"""

from __future__ import annotations

from typing import Any, Literal, TypedDict

SessionKind = Literal["problem", "topic", "lobby"]
CloseScope = Literal["none", "problem_segment", "session"]

MESSAGE_WINDOW = 16
DIGEST_EVERY_N_TURNS = 6


class SmartState(TypedDict, total=False):
    # messages：整表替换（非 append reducer），由节点维护窗口
    messages: list[Any]

    # —— durable：与 Java / checkpoint 对齐 ——
    session_id: str
    user_public_id: str
    session_kind: str
    topic: str
    problem_id: int
    phase: str
    intent: str
    turn_count: int
    summary: str
    refuse_short: bool

    # —— ephemeral：本回合 ——
    pending_action: str
    allow_code_原文: bool
    route: str
    reply: str
    offer_cta: str
    close_scope: str
    profile_digest: dict[str, Any]
    memory_digest: list[Any]
    topic_digest: dict[str, Any]
    offer_payload: dict[str, Any]
    pending_tool_rounds: int
    force_digest: bool  # persist 内是否 remember（L2 落库每回合必做）
    tokens_emitted: bool  # agent 已逐 token 推送时，finalize 不再分块重发
    intent_confidence: float
    injection_suspect: bool
    confirm_choices: list[Any]
