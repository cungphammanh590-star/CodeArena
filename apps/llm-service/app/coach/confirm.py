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


def build_confirm_payload(
    *,
    bound: bool,
    phase: str,
    session_kind: str,
    injection_suspect: bool = False,
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
        {"id": "status", "label": "今日进度", "text": CHOICE_STATUS},
        {"id": "meta", "label": "你会做什么", "text": CHOICE_META},
    ]
    return {"prompt": prompt, "choices": choices, "reason": "ambiguous"}
