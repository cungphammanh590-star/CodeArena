"""SmartState：durable 会话进展 vs ephemeral 本回合工作区。"""

from __future__ import annotations

from typing import Any, List, Literal, Optional, TypedDict

SessionKind = Literal["problem", "topic", "lobby"]
CloseScope = Literal["none", "problem_segment", "session"]

MESSAGE_WINDOW = 16
DIGEST_EVERY_N_TURNS = 6


class SmartState(TypedDict, total=False):
    # messages：整表替换（非 append reducer），由节点维护窗口
    # 注意：字段注解须兼容 Python 3.9（勿用 X | Y；LangGraph get_type_hints 会求值）
    messages: List[Any]

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
    solve_session: Optional[dict]  # {analysis, steps, replans, max_replans}
    paused_ask: Optional[dict]  # ask_user payload；非空表示挂起等待回复

    # —— ephemeral：本回合 ——
    pending_action: str
    allow_code_原文: bool
    route: str
    reply: str
    offer_cta: str
    close_scope: str
    profile_digest: dict
    memory_digest: List[Any]
    topic_digest: dict
    offer_payload: dict
    pending_tool_rounds: int
    force_digest: bool  # persist 内是否 remember（L2 落库每回合必做）
    tokens_emitted: bool  # agent 已逐 token 推送时，finalize 不再分块重发
    intent_confidence: float
    injection_suspect: bool
    confirm_choices: List[Any]
    solve_progress_event: Optional[dict]
    code_run_last: Optional[dict]
    awaiting_ask_user: bool  # 本回合 ask_user 触发，tools→finalize
    user_answers: List[Any]  # submit_user_reply 注入
    resume_from_ask: bool  # hydrate 恢复后强制 agent
