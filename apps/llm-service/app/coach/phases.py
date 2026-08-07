"""智能教练阶段与意图常量。"""

from __future__ import annotations

from typing import Literal

Phase = Literal["lobby", "today_brief", "prep", "in_problem", "wrap"]

PHASES: tuple[Phase, ...] = (
    "lobby",
    "today_brief",
    "prep",
    "in_problem",
    "wrap",
)

SmartIntent = Literal[
    "practice_continue",
    "practice_new",
    "status_review",
    "in_problem_help",
    "meta_product",
    "off_topic",
    "want_full_answer",
    "clarify",
]

ALLOWED_TRANSITIONS: dict[Phase, frozenset[Phase]] = {
    "lobby": frozenset(PHASES),
    "today_brief": frozenset({"lobby", "prep", "in_problem", "wrap", "today_brief"}),
    "prep": frozenset({"lobby", "prep", "in_problem", "wrap"}),
    "in_problem": frozenset({"in_problem", "wrap", "lobby", "prep"}),
    "wrap": frozenset({"lobby", "prep", "wrap", "in_problem"}),
}


def coerce_phase(value: str | None, *, default: Phase = "lobby") -> Phase:
    v = str(value or "").strip()
    return v if v in PHASES else default  # type: ignore[return-value]


def transition(current: Phase, target: Phase) -> Phase:
    allowed = ALLOWED_TRANSITIONS.get(current) or frozenset(PHASES)
    return target if target in allowed else current
