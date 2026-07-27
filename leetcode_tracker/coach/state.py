"""陪练 LangGraph 状态定义。"""

from __future__ import annotations

from typing import Any

from langgraph.graph import MessagesState

LOCAL_HARD_TURN_LIMIT = 8
API_DEEP_TURN_THRESHOLD = 10
API_COMPRESS_AFTER_TURNS = 8

END_PHRASES = ("结束", "够了", "先这样", "不用了", "谢谢")
NEGATION_PHRASES = ("不对", "不是", "错了", "换一个", "不是这个", "猜错了", "不是这里")
VAGUE_USER_PHRASES = ("不知道", "没思路", "帮我看", "怎么办", "提示一下", "？", "?")

ACTIONS = frozenset(
    {
        "",
        "close",
        "show_skeleton",
        "diagnose",
        "deep_analysis",
        "daily_review",
        "recommend",
        "optimize",
    }
)


class CoachState(MessagesState):
    context_markdown: str
    submission_status: str
    done: bool
    fallback_turn_count: int
    generation_error: str
    provider_failover: bool
    turn_count: int
    rejected_suspicions: list[str]
    mentioned_identifiers: list[str]
    exit_offered: bool
    degraded: bool
    pending_action: str
    problem_id: int
    last_assistant_text: str
    guardrail_stripped: bool
    consecutive_vague: int
    context_summary: str
    # Profile-centric
    user_profile: dict[str, Any]
    current_code: str
    intent: str
    analysis_result: str
    candidate_recommendations: list[dict[str, Any]]
