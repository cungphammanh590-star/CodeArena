"""Contract tests for deterministic coach prompt-policy assembly."""

from __future__ import annotations

from app.coach.prompt_policy import assemble_prompt, select_prompt_policies


def policy_names(state: dict) -> list[str]:
    return [item.policy.name for item in select_prompt_policies(state)]


def test_lobby_only_loads_core() -> None:
    assert policy_names({"phase": "lobby", "intent": "meta_product"}) == ["core"]


def test_planning_selected_by_phase_or_intent() -> None:
    assert policy_names({"phase": "plan_active", "intent": "clarify"}) == [
        "core",
        "planning",
    ]
    assert policy_names({"phase": "prep", "intent": "plan_create"}) == [
        "core",
        "planning",
    ]
    assert policy_names({"phase": "today_brief", "intent": "plan_status"}) == [
        "core",
        "planning",
        "status_review",
    ]


def test_status_review_does_not_load_problem_rules() -> None:
    names = policy_names({"phase": "today_brief", "intent": "status_review"})
    assert names == ["core", "status_review"]
    assert "in_problem" not in names


def test_problem_policy_selected_by_phase_or_intent() -> None:
    assert policy_names({"phase": "in_problem", "intent": "clarify"}) == [
        "core",
        "in_problem",
    ]
    assert policy_names({"phase": "lobby", "intent": "in_problem_help"}) == [
        "core",
        "in_problem",
    ]


def test_assembly_order_trace_and_runtime_separation() -> None:
    selected = select_prompt_policies({"phase": "plan_active", "intent": "plan_adjust"})
    result = assemble_prompt(
        selected,
        action_instruction="请调整计划。",
        runtime_context=["当前阶段 phase=plan_active。", "计划草稿：{...}"],
    )

    assert result.content.index("# Policy: core") < result.content.index("# Policy: planning")
    assert result.content.index("# Policy: planning") < result.content.index("# 本回合动作")
    assert result.content.index("# 本回合动作") < result.content.index("# 运行时上下文")
    assert result.trace["policy_names"] == ["core", "planning"]
    assert result.trace["policies"][0]["reason"] == "always"
    assert result.trace["estimated_tokens"] > 0
    assert result.trace["chars"] == len(result.content)


def test_assembly_is_deterministic() -> None:
    state = {"phase": "in_problem", "intent": "in_problem_help"}
    first = assemble_prompt(
        select_prompt_policies(state), runtime_context=["problem_id=1"]
    )
    second = assemble_prompt(
        select_prompt_policies(state), runtime_context=["problem_id=1"]
    )
    assert first == second


def test_core_safety_contract_is_always_present() -> None:
    for state in (
        {"phase": "lobby", "intent": "clarify"},
        {"phase": "plan_active", "intent": "plan_create"},
        {"phase": "in_problem", "intent": "want_full_answer"},
    ):
        result = assemble_prompt(select_prompt_policies(state))
        assert "禁止整题完整可运行解法" in result.content
        assert "题号只能来自工具返回的候选" in result.content
        assert result.trace["policy_names"][0] == "core"
