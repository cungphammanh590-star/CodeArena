"""LOCAL coach tools: code_execution, solve_*, ask_user."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from typing import Any

from app.coach.solve.session import SolveSession, parse_step_goals
from app.sandbox.service import SandboxService, get_sandbox_service
from app.sandbox.spec import ExecRequest, ResourceLimits, truncate_preview

SOLVE_TOOL_NAMES = frozenset({"solve_plan", "solve_finish_step", "solve_replan"})
CODE_TOOL_NAMES = frozenset({"code_execution"})
ASK_TOOL_NAMES = frozenset({"ask_user"})
LOCAL_P0_TOOL_NAMES = SOLVE_TOOL_NAMES | CODE_TOOL_NAMES | ASK_TOOL_NAMES | frozenset(
    {"get_last_advice"}
)

_SUPPORTED_LANG = {
    "python": (".py", ["python3"]),
    "py": (".py", ["python3"]),
}


@dataclass
class LocalToolResult:
    content: str
    state_updates: dict[str, Any] = field(default_factory=dict)
    pause: bool = False
    sse_events: list[dict[str, Any]] = field(default_factory=list)


def _json(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False)


def execute_local_tool(
    *,
    tool_name: str,
    params: dict[str, Any] | None,
    state: dict[str, Any],
    history: list[dict[str, str]] | None = None,
    tool_call_id: str = "",
    sandbox: SandboxService | None = None,
) -> LocalToolResult | None:
    """Dispatch LOCAL tools. Returns None if not a local tool."""
    name = (tool_name or "").strip()
    args = params or {}
    if name == "get_last_advice":
        return _get_last_advice(history or [])
    if name == "code_execution":
        return _code_execution(args, state, sandbox=sandbox or get_sandbox_service())
    if name == "solve_plan":
        return _solve_plan(args, state)
    if name == "solve_finish_step":
        return _solve_finish_step(args, state)
    if name == "solve_replan":
        return _solve_replan(args, state)
    if name == "ask_user":
        return _ask_user(args, tool_call_id=tool_call_id)
    return None


def _get_last_advice(history: list[dict[str, str]]) -> LocalToolResult:
    advice = ""
    for msg in reversed(history):
        if msg.get("role") == "assistant" and msg.get("content"):
            advice = str(msg["content"])
            break
    return LocalToolResult(
        content=_json(
            {
                "ok": bool(advice),
                "advice": advice,
                "note": "本地会话历史" if advice else "无历史建议",
                "executor": "llm-service-local",
            }
        )
    )


def _code_execution(
    args: dict[str, Any],
    state: dict[str, Any],
    *,
    sandbox: SandboxService,
) -> LocalToolResult:
    language = str(args.get("language") or "python").strip().lower()
    code = str(args.get("code") or "")
    stdin = str(args.get("stdin") or "")
    timeout_raw = args.get("timeout")
    try:
        timeout = int(timeout_raw) if timeout_raw is not None else sandbox.settings.timeout_s
    except (TypeError, ValueError):
        timeout = sandbox.settings.timeout_s
    timeout = max(1, min(60, timeout))

    if language not in _SUPPORTED_LANG:
        return LocalToolResult(
            content=_json(
                {
                    "ok": False,
                    "error": f"unsupported language={language}; P0 supports python",
                }
            )
        )
    if not code.strip():
        return LocalToolResult(content=_json({"ok": False, "error": "code is empty"}))

    ext, argv_prefix = _SUPPORTED_LANG[language]
    user_id = str(state.get("user_public_id") or "anon")
    session_id = str(state.get("session_id") or "unknown")
    run_id = uuid.uuid4().hex[:12]
    workdir = sandbox.run_dir(user_id=user_id, session_id=session_id, run_id=run_id)
    source_path = workdir / f"main{ext}"
    source_path.write_text(code, encoding="utf-8")
    if stdin:
        (workdir / "stdin.txt").write_text(stdin, encoding="utf-8")

    limits = ResourceLimits(
        timeout_s=timeout,
        memory_mb=sandbox.settings.memory_mb,
        max_output_chars=sandbox.settings.max_output_chars,
        cpu_seconds=timeout,
    )
    argv = [*argv_prefix, str(source_path)]
    request = ExecRequest.of_argv(argv, workdir=str(workdir), limits=limits)
    result = sandbox.run(request, user_id=user_id)

    snippet_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()[:16]
    preview = {
        "language": "python" if language in {"python", "py"} else language,
        "exit_code": result.exit_code,
        "timed_out": result.timed_out,
        "duration_ms": result.duration_ms,
        "stdout_preview": truncate_preview(result.stdout, 1500),
        "stderr_preview": truncate_preview(result.stderr, 800),
        "error": result.error,
        "snippet_hash": snippet_hash,
        "ok": result.ok and result.exit_code == 0 and not result.error,
    }
    payload = {
        **preview,
        "render": result.render(sandbox.settings.max_output_chars),
        "executor": "llm-service-sandbox",
    }
    return LocalToolResult(
        content=_json(payload),
        state_updates={"code_run_last": preview},
        sse_events=[{"type": "code_result", **preview}],
    )


def _load_session(state: dict[str, Any]) -> SolveSession:
    existing = SolveSession.from_dict(state.get("solve_session"))
    return existing or SolveSession()


def _solve_plan(args: dict[str, Any], state: dict[str, Any]) -> LocalToolResult:
    steps = parse_step_goals(args.get("steps"))
    if not steps:
        return LocalToolResult(
            content=_json(
                {
                    "ok": False,
                    "error": "solve_plan needs a non-empty steps array, each with a goal",
                }
            )
        )
    analysis = str(args.get("analysis") or "").strip()
    session = _load_session(state)
    session.set_plan(analysis, steps)
    progress = session.progress_payload()
    payload = {
        "ok": True,
        "status": "planned",
        **progress,
        "instruction": (
            "先完成第一步，再用 solve_finish_step 提交摘要；不要跳步，不要贴完整 AC。"
        ),
    }
    return LocalToolResult(
        content=_json(payload),
        state_updates={
            "solve_session": session.to_dict(),
            "solve_progress_event": progress,
        },
        sse_events=[{"type": "solve_progress", **progress}],
    )


def _solve_finish_step(args: dict[str, Any], state: dict[str, Any]) -> LocalToolResult:
    session = _load_session(state)
    if not session.steps:
        return LocalToolResult(
            content=_json({"ok": False, "error": "No plan yet. Call solve_plan first."})
        )
    step_id = str(args.get("step_id") or "").strip()
    summary = str(args.get("summary") or "").strip()
    step = session.mark_done(step_id, summary)
    if step is None:
        return LocalToolResult(
            content=_json(
                {
                    "ok": False,
                    "error": f"Unknown step {step_id!r}",
                    "valid": [s.id for s in session.steps],
                }
            )
        )
    progress = session.progress_payload()
    payload = {
        "ok": True,
        "status": "step_done",
        "completed": step_id,
        **progress,
        "instruction": (
            "可以收束本题思路（仍不要贴完整可提交代码）。"
            if progress["all_done"]
            else "继续下一步，完成后调用 solve_finish_step。"
        ),
    }
    return LocalToolResult(
        content=_json(payload),
        state_updates={
            "solve_session": session.to_dict(),
            "solve_progress_event": progress,
        },
        sse_events=[{"type": "solve_progress", **progress}],
    )


def _solve_replan(args: dict[str, Any], state: dict[str, Any]) -> LocalToolResult:
    session = _load_session(state)
    steps = parse_step_goals(args.get("steps"))
    if not steps:
        return LocalToolResult(
            content=_json({"ok": False, "error": "solve_replan needs non-empty steps"})
        )
    reason = str(args.get("reason") or "").strip()
    if not session.replan(reason, steps):
        return LocalToolResult(
            content=_json(
                {
                    "ok": False,
                    "status": "replan_budget_exhausted",
                    "replans_used": session.replans,
                    "replans_max": session.max_replans,
                    "steps": [s.to_dict() for s in session.steps],
                    "error": "replan budget exhausted; keep current plan",
                }
            )
        )
    progress = session.progress_payload()
    payload = {
        "ok": True,
        "status": "replanned",
        "replans_used": session.replans,
        "replans_max": session.max_replans,
        **progress,
        "instruction": "按新计划从 next 步继续。",
    }
    return LocalToolResult(
        content=_json(payload),
        state_updates={
            "solve_session": session.to_dict(),
            "solve_progress_event": progress,
        },
        sse_events=[{"type": "solve_progress", **progress}],
    )


def _ask_user(args: dict[str, Any], *, tool_call_id: str) -> LocalToolResult:
    intro = str(args.get("intro") or "").strip() or None
    raw_qs = args.get("questions")
    if not isinstance(raw_qs, list) or not raw_qs:
        return LocalToolResult(
            content=_json({"ok": False, "error": "ask_user needs 1–4 questions"})
        )
    questions: list[dict[str, Any]] = []
    for i, raw in enumerate(raw_qs[:4]):
        if not isinstance(raw, dict):
            continue
        prompt = str(raw.get("prompt") or "").strip()
        if not prompt:
            continue
        qid = str(raw.get("id") or f"q{i + 1}").strip() or f"q{i + 1}"
        options_raw = raw.get("options") or []
        options: list[dict[str, str]] = []
        if isinstance(options_raw, list):
            for opt in options_raw:
                if isinstance(opt, dict):
                    label = str(opt.get("label") or "").strip()
                    if label:
                        options.append(
                            {
                                "label": label,
                                "description": str(opt.get("description") or "").strip(),
                            }
                        )
                elif str(opt).strip():
                    options.append({"label": str(opt).strip(), "description": ""})
        questions.append(
            {
                "id": qid,
                "prompt": prompt,
                "header": str(raw.get("header") or "").strip() or None,
                "options": options or None,
                "multi_select": bool(raw.get("multi_select")),
                "allow_free_text": bool(raw.get("allow_free_text", True)),
                "placeholder": str(raw.get("placeholder") or "").strip() or None,
            }
        )
    if not questions:
        return LocalToolResult(
            content=_json({"ok": False, "error": "ask_user needs at least one valid question"})
        )

    payload = {"intro": intro, "questions": questions}
    paused = {**payload, "tool_call_id": tool_call_id or "ask_user"}
    return LocalToolResult(
        content=_json(
            {
                "ok": True,
                "status": "paused",
                "message": "Waiting for user reply via submit_user_reply",
                **payload,
            }
        ),
        state_updates={"paused_ask": paused, "awaiting_ask_user": True},
        pause=True,
        sse_events=[{"type": "ask_user", **payload}],
    )


def format_user_answers(answers: list[dict[str, Any]], paused: dict[str, Any]) -> str:
    """Format FE answers into a tool-result string for the model."""
    by_id = {
        str(a.get("question_id") or "").strip(): str(a.get("text") or "").strip()
        for a in answers
        if isinstance(a, dict)
    }
    lines = ["User answered:"]
    for q in paused.get("questions") or []:
        if not isinstance(q, dict):
            continue
        qid = str(q.get("id") or "")
        prompt = str(q.get("prompt") or "")
        text = by_id.get(qid) or ""
        lines.append(f"- [{qid}] {prompt}: {text or '(empty)'}")
    leftover = {k: v for k, v in by_id.items() if k and all(
        str(q.get("id")) != k for q in (paused.get("questions") or []) if isinstance(q, dict)
    )}
    for k, v in leftover.items():
        lines.append(f"- [{k}]: {v}")
    lines.append("Continue with the user's answers. Do not ask the same questions again.")
    return "\n".join(lines)


def tool_specs_for_state(state: dict[str, Any], all_specs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Phase/intent-aware tool gating for agent bind_tools."""
    phase = str(state.get("phase") or "")
    intent = str(state.get("intent") or "")
    route = str(state.get("route") or "")
    problem_id = int(state.get("problem_id") or 0)

    in_problem = phase == "in_problem" or intent == "in_problem_help" or problem_id > 0
    allow_ask = route == "agent"  # classify already chose agent; mid-turn ask ok

    blocked: set[str] = set()
    if not in_problem:
        blocked |= SOLVE_TOOL_NAMES | CODE_TOOL_NAMES
    if not allow_ask and intent not in {"clarify", "plan_create", "in_problem_help"}:
        # still allow ask_user on agent path for plan/clarify; default allow when bound to agent
        pass
    # lobby/offer never bind solve/code — already handled by in_problem
    # ask_user: available whenever tools are bound on agent path
    if route in {"offer", "refuse", "confirm"}:
        blocked |= SOLVE_TOOL_NAMES | CODE_TOOL_NAMES | ASK_TOOL_NAMES

    out: list[dict[str, Any]] = []
    for spec in all_specs:
        name = str((spec.get("function") or {}).get("name") or "")
        if name in blocked:
            continue
        out.append(spec)
    return out


def max_tool_rounds_for_state(state: dict[str, Any], default: int = 3) -> int:
    phase = str(state.get("phase") or "")
    intent = str(state.get("intent") or "")
    if phase == "in_problem" or intent == "in_problem_help":
        return 5
    return default


__all__ = [
    "ASK_TOOL_NAMES",
    "CODE_TOOL_NAMES",
    "LOCAL_P0_TOOL_NAMES",
    "LocalToolResult",
    "SOLVE_TOOL_NAMES",
    "execute_local_tool",
    "format_user_answers",
    "max_tool_rounds_for_state",
    "tool_specs_for_state",
]
