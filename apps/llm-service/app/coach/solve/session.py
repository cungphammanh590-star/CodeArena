"""SolveSession — checkpoint-bound plan / step / replan spine."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

DEFAULT_MAX_REPLANS = 2
_MAX_STEPS = 12


@dataclass
class SolveStep:
    id: str
    goal: str
    done: bool = False
    summary: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "goal": self.goal,
            "done": self.done,
            "summary": self.summary,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> SolveStep:
        return cls(
            id=str(raw.get("id") or ""),
            goal=str(raw.get("goal") or ""),
            done=bool(raw.get("done")),
            summary=str(raw.get("summary") or ""),
        )


@dataclass
class SolveSession:
    analysis: str = ""
    steps: list[SolveStep] = field(default_factory=list)
    replans: int = 0
    max_replans: int = DEFAULT_MAX_REPLANS

    def set_plan(self, analysis: str, steps: list[tuple[str, str]]) -> None:
        self.analysis = analysis
        self.steps = [SolveStep(id=sid, goal=goal) for sid, goal in steps][:_MAX_STEPS]

    def replan(self, reason: str, steps: list[tuple[str, str]]) -> bool:
        if self.replans >= self.max_replans:
            return False
        self.replans += 1
        analysis = (reason or "").strip() or self.analysis
        self.set_plan(analysis, steps)
        return True

    def mark_done(self, step_id: str, summary: str) -> SolveStep | None:
        for step in self.steps:
            if step.id == step_id:
                step.done = True
                step.summary = summary.strip()
                return step
        return None

    def next_step(self) -> SolveStep | None:
        return next((step for step in self.steps if not step.done), None)

    def all_done(self) -> bool:
        return bool(self.steps) and all(step.done for step in self.steps)

    def progress_payload(self) -> dict[str, Any]:
        nxt = self.next_step()
        return {
            "analysis": self.analysis,
            "steps": [s.to_dict() for s in self.steps],
            "next": nxt.to_dict() if nxt else None,
            "all_done": self.all_done(),
            "replans": self.replans,
            "max_replans": self.max_replans,
        }

    def summary_tag(self) -> str:
        if not self.steps:
            return ""
        done = sum(1 for s in self.steps if s.done)
        return f"solve: S{done}/{len(self.steps)}" if done else f"solve: S0/{len(self.steps)}"

    def to_dict(self) -> dict[str, Any]:
        return {
            "analysis": self.analysis,
            "steps": [s.to_dict() for s in self.steps],
            "replans": self.replans,
            "max_replans": self.max_replans,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any] | None) -> SolveSession | None:
        if not isinstance(raw, dict) or not raw:
            return None
        steps_raw = raw.get("steps") or []
        steps = [
            SolveStep.from_dict(s) for s in steps_raw if isinstance(s, dict)
        ]
        return cls(
            analysis=str(raw.get("analysis") or ""),
            steps=steps,
            replans=int(raw.get("replans") or 0),
            max_replans=int(raw.get("max_replans") or DEFAULT_MAX_REPLANS),
        )


def parse_step_goals(raw_steps: Any) -> list[tuple[str, str]]:
    if not isinstance(raw_steps, list):
        return []
    steps: list[tuple[str, str]] = []
    for raw in raw_steps:
        if isinstance(raw, dict):
            goal = str(raw.get("goal") or "").strip()
        else:
            goal = str(raw or "").strip()
        if not goal:
            continue
        steps.append((f"S{len(steps) + 1}", goal))
    return steps


__all__ = [
    "DEFAULT_MAX_REPLANS",
    "SolveSession",
    "SolveStep",
    "parse_step_goals",
]
