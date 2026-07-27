"""本地图失稳 / 硬上限检测。"""

from __future__ import annotations

import re
from typing import Any

from leetcode_tracker.coach.state import (
    LOCAL_HARD_TURN_LIMIT,
    VAGUE_USER_PHRASES,
)


def _normalize(text: str) -> str:
    t = (text or "").strip().lower()
    t = re.sub(r"\s+", "", t)
    return t


def jaccard_similarity(a: str, b: str) -> float:
    sa, sb = _normalize(a), _normalize(b)
    if not sa or not sb:
        return 0.0
    # 字符 2-gram
    def grams(s: str) -> set[str]:
        if len(s) < 2:
            return {s}
        return {s[i : i + 2] for i in range(len(s) - 1)}

    ga, gb = grams(sa), grams(sb)
    inter = len(ga & gb)
    union = len(ga | gb)
    return inter / union if union else 0.0


def is_vague_user_message(text: str) -> bool:
    t = (text or "").strip()
    if len(t) <= 4:
        return True
    return any(p in t for p in VAGUE_USER_PHRASES)


def should_offer_exit(state: dict[str, Any], *, user_message: str = "") -> tuple[bool, str]:
    """返回 (是否提议出口, 原因码)。已提议过仍可再次检测，但调用方通常跳过。"""
    turn = int(state.get("turn_count") or 0)
    if turn >= LOCAL_HARD_TURN_LIMIT:
        return True, "hard_turn_limit"
    if bool(state.get("degraded")):
        return True, "degraded"
    if bool(state.get("guardrail_stripped")) and turn >= 2:
        return True, "guardrail"
    last = str(state.get("last_assistant_text") or "")
    # 复读：需要上一轮助手文本；在 coach_reply 之后用新回复比 last
    vague_n = int(state.get("consecutive_vague") or 0)
    if vague_n >= 2:
        return True, "vague_loop"
    if user_message and is_vague_user_message(user_message) and vague_n >= 1 and turn >= 3:
        return True, "vague_loop"
    if last and turn >= 2:
        # 由调用方在拿到新回复后再比；此处仅硬规则
        pass
    return False, ""


def is_repetitive_reply(previous: str, current: str, *, threshold: float = 0.72) -> bool:
    if not previous or not current:
        return False
    return jaccard_similarity(previous, current) >= threshold
