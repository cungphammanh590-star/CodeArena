"""意图识别：规则优先；Local 判别式标签；Api 结构化分类。"""

from __future__ import annotations

import re
from typing import Any, Literal, Optional

Intent = Literal[
    "optimize",
    "recommend",
    "daily_review",
    "chat",
    "show_answer",
]

INTENTS: tuple[Intent, ...] = (
    "optimize",
    "recommend",
    "daily_review",
    "chat",
    "show_answer",
)

# action → 直达路由（跳过意图分类）
ACTION_TO_ROUTE: dict[str, str] = {
    "close": "close_session",
    "show_skeleton": "answer_egress",
    "diagnose": "diagnose",
    "deep_analysis": "deep_analysis",
    "daily_review": "daily_review",
    "recommend": "recommend",
    "optimize": "optimize",
}

_RULES: list[tuple[Intent, tuple[str, ...]]] = [
    (
        "recommend",
        ("换一题", "下一题", "下一道", "推荐", "换题", "做哪题", "刷什么"),
    ),
    (
        "daily_review",
        ("今日总结", "今天刷", "今天怎么样", "每日回顾", "今日回顾", "掌握程度", "今天进度"),
    ),
    (
        "optimize",
        ("优化", "超时", "TLE", "太慢", "复杂度", "更快", "性能", "怎么优"),
    ),
    (
        "show_answer",
        ("看思路", "看答案", "标准解", "题解", "直接告诉我答案"),
    ),
]


def classify_by_rules(text: str) -> Optional[Intent]:
    t = (text or "").strip()
    if not t:
        return None
    for intent, phrases in _RULES:
        if any(p.lower() in t.lower() if p.isascii() else p in t for p in phrases):
            return intent
    return None


def parse_intent_label(raw: str) -> Intent:
    text = (raw or "").strip().lower()
    # 中文标签
    mapping = {
        "优化": "optimize",
        "推荐": "recommend",
        "每日回顾": "daily_review",
        "日回顾": "daily_review",
        "回顾": "daily_review",
        "闲聊": "chat",
        "答疑": "chat",
        "看思路": "show_answer",
        "答案": "show_answer",
    }
    for k, v in mapping.items():
        if k in (raw or ""):
            return v  # type: ignore[return-value]
    for intent in INTENTS:
        if intent in text:
            return intent
    m = re.search(
        r"\b(optimize|recommend|daily_review|chat|show_answer)\b",
        text,
    )
    if m:
        return m.group(1)  # type: ignore[return-value]
    return "chat"


def classify_local_discriminative(text: str, *, invoke_llm) -> Intent:
    """规则未命中时：极简 Prompt 只输出标签。invoke_llm(prompt)->str。"""
    ruled = classify_by_rules(text)
    if ruled:
        return ruled
    prompt = (
        "将用户消息分类为以下标签之一，只输出标签本身，不要解释：\n"
        "优化 / 推荐 / 每日回顾 / 闲聊\n\n"
        f"用户消息：{text[:200]}\n"
        "标签："
    )
    try:
        raw = invoke_llm(prompt)
        return parse_intent_label(str(raw or ""))
    except Exception:  # noqa: BLE001
        return "chat"


def classify_api_structured(text: str, *, invoke_llm) -> Intent:
    """Api：要求 JSON/标签；失败回退 chat。"""
    ruled = classify_by_rules(text)
    if ruled:
        return ruled
    prompt = (
        "Classify the user message into exactly one intent label.\n"
        "Labels: optimize | recommend | daily_review | chat | show_answer\n"
        "Reply with ONLY the label.\n\n"
        f"User: {text[:400]}\n"
        "Label:"
    )
    try:
        raw = invoke_llm(prompt)
        return parse_intent_label(str(raw or ""))
    except Exception:  # noqa: BLE001
        return "chat"


def resolve_route(
    *,
    action: str,
    intent: str,
    provider: str,
) -> str:
    """将 action/intent 映射到图节点名。"""
    action = (action or "").strip()
    if action in ACTION_TO_ROUTE:
        route = ACTION_TO_ROUTE[action]
        # Api 模式 show_skeleton → diagnose（与既有行为一致）
        if provider == "api" and action == "show_skeleton":
            return "diagnose"
        return route
    intent = (intent or "chat").strip() or "chat"
    if intent == "optimize":
        return "optimize"
    if intent == "recommend":
        return "recommend"
    if intent == "daily_review":
        return "daily_review"
    if intent == "show_answer":
        return "answer_egress" if provider != "api" else "diagnose"
    return "coach_reply"


def sync_invoke_label(prompt: str) -> str:
    """同步调用当前 chat 模型，取短标签。"""
    from leetcode_tracker.llm.provider import build_chat_model

    model = build_chat_model()
    result = model.invoke(prompt)
    content = getattr(result, "content", None)
    if isinstance(content, str):
        return content
    return str(content or result or "")
