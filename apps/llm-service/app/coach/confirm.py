"""用户确认下一步：低置信 / 灰区时给出可点击选项。"""

from __future__ import annotations

from typing import Any

# 点击后嵌入对话框的固定文案（须与 intent_smart 高置信匹配）
CHOICE_CONTINUE = "继续刷上次没过的题"
CHOICE_NEW = "推荐一道新题"
CHOICE_STATUS = "看看今天刷题进度"
CHOICE_IN_PROBLEM = "继续帮我看这道题"
CHOICE_META = "先说明你会做什么"
CHOICE_BACK = "先回到刷题"
CHOICE_PLAN_GOOGLE = "请为我生成 Google 面试备考、30天的刷题计划"
CHOICE_PLAN_DP = "请为我生成动态规划专题、14天的刷题计划"
CHOICE_PLAN_HOT100 = "请为我按 Hot100 题单生成 21 天打卡计划"
CHOICE_TODAY_TASKS = "请展示今日计划任务"
CHOICE_START_FIRST = "绑定今日第一题开始跟练"
CHOICE_ADJUST_PLAN = "我想调整计划天数或每日题量"


def build_confirm_payload(
    *,
    bound: bool,
    phase: str,
    session_kind: str,
    injection_suspect: bool = False,
    intent: str = "",
) -> dict[str, Any]:
    """返回 prompt + choices；choice.text 供前端原样发送。"""
    if injection_suspect:
        prompt = (
            "这句话里有些指令不太像刷题对话。请选一个明确的下一步"
            "（或换种说法描述你的题目疑问）："
        )
        choices = [
            {"id": "continue", "label": "续刷未过", "text": CHOICE_CONTINUE},
            {"id": "new", "label": "推荐新题", "text": CHOICE_NEW},
            {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
            {"id": "back", "label": "回到刷题", "text": CHOICE_BACK},
        ]
        return {"prompt": prompt, "choices": choices, "reason": "injection"}

    if phase == "plan_active" and intent in {"plan_status", "status_review", "clarify"}:
        prompt = "计划相关，你想先做什么？"
        choices = [
            {"id": "today", "label": "看今日任务", "text": CHOICE_TODAY_TASKS},
            {"id": "start", "label": "开刷第一题", "text": CHOICE_START_FIRST},
            {"id": "adjust", "label": "调整计划", "text": CHOICE_ADJUST_PLAN},
            {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
        ]
        return {"prompt": prompt, "choices": choices, "reason": "plan_followup"}

    if intent == "plan_create" or phase == "plan_active":
        prompt = "想按哪个目标生成刷题计划？选一个（或直接说公司/专题 + 天数）："
        choices = [
            {"id": "plan_google", "label": "Google·30天", "text": CHOICE_PLAN_GOOGLE},
            {"id": "plan_dp", "label": "动态规划·14天", "text": CHOICE_PLAN_DP},
            {"id": "plan_hot100", "label": "Hot100·21天", "text": CHOICE_PLAN_HOT100},
            {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
        ]
        return {"prompt": prompt, "choices": choices, "reason": "plan_goal"}

    if bound or phase == "in_problem":
        prompt = "我还不太确定你的意图，请选一个下一步："
        choices = [
            {"id": "help", "label": "继续看这题", "text": CHOICE_IN_PROBLEM},
            {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
            {"id": "new", "label": "换一题", "text": CHOICE_NEW},
            {"id": "continue", "label": "续刷未过", "text": CHOICE_CONTINUE},
        ]
        return {"prompt": prompt, "choices": choices, "reason": "ambiguous"}

    kind = session_kind or "lobby"
    if kind == "topic":
        prompt = "专题聊到这儿，你想接下来做什么？"
    else:
        prompt = "我不太确定你的下一步，请选一个："

    choices = [
        {"id": "continue", "label": "续刷未过", "text": CHOICE_CONTINUE},
        {"id": "new", "label": "推荐新题", "text": CHOICE_NEW},
        {"id": "plan_dp", "label": "生成专题计划", "text": CHOICE_PLAN_DP},
        {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
        {"id": "meta", "label": "你会做什么", "text": CHOICE_META},
    ]
    return {"prompt": prompt, "choices": choices, "reason": "ambiguous"}
