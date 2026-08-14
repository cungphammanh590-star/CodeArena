"""Unit tests for sandbox + solve local tools."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.coach.local_tools import execute_local_tool, format_user_answers, tool_specs_for_state
from app.coach.solve.session import SolveSession, parse_step_goals
from app.sandbox.backends import RestrictedSubprocessBackend
from app.sandbox.service import SandboxService, SandboxSettings, reset_sandbox_service
from app.sandbox.spec import ExecRequest, ResourceLimits
from app.services.tool_client import TOOL_SPECS


@pytest.fixture()
def sandbox(tmp_path: Path) -> SandboxService:
    settings = SandboxSettings(
        backend="subprocess",
        timeout_s=5,
        data_dir=str(tmp_path / "runs"),
        max_concurrent_per_user=1,
        max_runs_per_minute_per_user=20,
    )
    svc = SandboxService(settings, backend=RestrictedSubprocessBackend())
    reset_sandbox_service(svc)
    yield svc
    reset_sandbox_service(None)


def test_subprocess_runs_python(sandbox: SandboxService, tmp_path: Path) -> None:
    work = tmp_path / "w"
    work.mkdir()
    src = work / "main.py"
    src.write_text("print(1+1)\n", encoding="utf-8")
    result = sandbox.run(
        ExecRequest.of_argv(
            ["python3", str(src)],
            workdir=str(work),
            limits=ResourceLimits(timeout_s=5),
        ),
        user_id="u1",
    )
    assert result.ok
    assert result.exit_code == 0
    assert "2" in result.stdout


def test_code_execution_local_tool(sandbox: SandboxService) -> None:
    out = execute_local_tool(
        tool_name="code_execution",
        params={"language": "python", "code": "print('hi')\n"},
        state={"user_public_id": "usr_test", "session_id": "sess1"},
        sandbox=sandbox,
    )
    assert out is not None
    payload = json.loads(out.content)
    assert payload.get("exit_code") == 0
    assert "hi" in (payload.get("stdout_preview") or "")
    assert out.sse_events and out.sse_events[0]["type"] == "code_result"


def test_solve_plan_finish_all_done() -> None:
    state: dict = {}
    plan = execute_local_tool(
        tool_name="solve_plan",
        params={
            "analysis": "two sum",
            "steps": [{"goal": "理解题意"}, {"goal": "写出哈希思路"}],
        },
        state=state,
    )
    assert plan is not None
    state.update(plan.state_updates)
    assert len(state["solve_session"]["steps"]) == 2

    fin1 = execute_local_tool(
        tool_name="solve_finish_step",
        params={"step_id": "S1", "summary": "ok"},
        state=state,
    )
    assert fin1 is not None
    state.update(fin1.state_updates)
    assert json.loads(fin1.content)["all_done"] is False

    fin2 = execute_local_tool(
        tool_name="solve_finish_step",
        params={"step_id": "S2", "summary": "done"},
        state=state,
    )
    assert fin2 is not None
    state.update(fin2.state_updates)
    assert json.loads(fin2.content)["all_done"] is True
    sess = SolveSession.from_dict(state["solve_session"])
    assert sess is not None
    assert sess.summary_tag() == "solve: S2/2"


def test_solve_replan_budget() -> None:
    state: dict = {}
    execute_local_tool(
        tool_name="solve_plan",
        params={"analysis": "a", "steps": [{"goal": "s1"}, {"goal": "s2"}]},
        state=state,
    )
    # seed state from plan
    plan = execute_local_tool(
        tool_name="solve_plan",
        params={"analysis": "a", "steps": [{"goal": "s1"}, {"goal": "s2"}]},
        state={},
    )
    assert plan
    state = {**plan.state_updates}

    for i in range(2):
        r = execute_local_tool(
            tool_name="solve_replan",
            params={"reason": f"r{i}", "steps": [{"goal": f"n{i}"}]},
            state=state,
        )
        assert r is not None
        assert json.loads(r.content).get("ok") is True
        state.update(r.state_updates)

    exhausted = execute_local_tool(
        tool_name="solve_replan",
        params={"reason": "again", "steps": [{"goal": "x"}]},
        state=state,
    )
    assert exhausted is not None
    body = json.loads(exhausted.content)
    assert body.get("ok") is False
    assert body.get("status") == "replan_budget_exhausted"
    # plan preserved
    assert state["solve_session"]["steps"][0]["goal"] == "n1"


def test_ask_user_pause_and_format_answers() -> None:
    out = execute_local_tool(
        tool_name="ask_user",
        params={
            "intro": "先确认目标",
            "questions": [
                {"id": "q1", "prompt": "目标公司？", "options": [{"label": "Google"}]},
                {"prompt": "天数？", "allow_free_text": True},
            ],
        },
        state={},
        tool_call_id="call_ask_1",
    )
    assert out is not None
    assert out.pause is True
    assert out.state_updates.get("paused_ask")
    assert out.sse_events[0]["type"] == "ask_user"

    text = format_user_answers(
        [{"question_id": "q1", "text": "Google"}, {"question_id": "q2", "text": "14"}],
        out.state_updates["paused_ask"],
    )
    assert "Google" in text
    assert "14" in text


def test_tool_gating_lobby_hides_solve() -> None:
    specs = tool_specs_for_state(
        {"phase": "lobby", "intent": "meta_product", "route": "agent", "problem_id": 0},
        TOOL_SPECS,
    )
    names = {str(s["function"]["name"]) for s in specs}
    assert "solve_plan" not in names
    assert "code_execution" not in names
    assert "get_last_advice" in names

    specs2 = tool_specs_for_state(
        {"phase": "in_problem", "intent": "in_problem_help", "route": "agent", "problem_id": 1},
        TOOL_SPECS,
    )
    names2 = {str(s["function"]["name"]) for s in specs2}
    assert "solve_plan" in names2
    assert "code_execution" in names2


def test_parse_step_goals() -> None:
    assert parse_step_goals([{"goal": "a"}, {"goal": ""}, {"goal": "b"}]) == [
        ("S1", "a"),
        ("S2", "b"),
    ]
