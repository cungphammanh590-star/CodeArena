"""LangGraph tool specs + Java executor callback client.

LLM 只决策 tool_name/params；数据访问一律回调 business-service
``POST /internal/tools/exec``（编排器-执行器）。
"""

from __future__ import annotations

import json
from typing import Any, Optional

import httpx

from app.config import Settings, get_settings

MAX_TOOL_ROUNDS = 3

# 与上游 leetcode-tracker smart_agent 对齐的工具清单（Java 执行）
JAVA_TOOL_SPECS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_session_binding",
            "description": "查看当前会话是否已绑定题目。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "bind_problem",
            "description": (
                "将本会话绑定到一道题。用户给出题号或标题时调用。"
                "多候选时不要猜测，把 candidates 转述给用户确认。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "problem_id": {
                        "type": "integer",
                        "description": "力扣题号，如 215",
                    },
                    "query": {
                        "type": "string",
                        "description": "标题或 slug 关键词；无题号时使用",
                    },
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_code",
            "description": "读取当前绑定题目最新提交的代码与状态。禁止也不提供历史 Accepted 源码。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_error_summary",
            "description": "读取已绑定题目的错因分布、挣扎指数与标签等统计要点。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_latest_submission",
            "description": "读取该用户最近一条提交的题号/状态（不含源码）。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_unpassed_problems",
            "description": "列出近期未通过（非 AC）的题目，用于续刷提议。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_user_profile_summary",
            "description": "读取用户整体画像：提交量、掌握题数等。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_topic_mastery",
            "description": "按标签/知识点聚合掌握与挣扎情况。",
            "parameters": {
                "type": "object",
                "properties": {
                    "topic": {"type": "string", "description": "标签名，如 动态规划"},
                },
                "required": ["topic"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_problem_mastery",
            "description": "单题掌握/挣扎统计；可省略 problem_id 则用当前绑定题。",
            "parameters": {
                "type": "object",
                "properties": {
                    "problem_id": {"type": "integer"},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "suggest_next_problems",
            "description": (
                "选题：有未通过则返回续刷候选，否则返回规则引擎新题候选。"
                "禁止推荐返回列表外的题号。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {"type": "integer", "description": "候选数量 1～5"},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "recall_memories",
            "description": "读取用户跨会话长期记忆（偏好/薄弱点/目标/笔记）。开场或选题前可调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "kind": {
                        "type": "string",
                        "description": "preference|weakness|coach_note|goal；省略则全部",
                    },
                    "limit": {"type": "integer", "description": "条数 1～20"},
                },
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "remember",
            "description": (
                "把值得跨会话保留的事实写入长期记忆。"
                "例如学习偏好、明确目标、反复出现的薄弱点。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "记忆正文"},
                    "kind": {
                        "type": "string",
                        "description": "preference|weakness|coach_note|goal",
                    },
                    "source": {"type": "string", "description": "user|coach|system"},
                    "problem_id": {"type": "integer"},
                    "confidence": {"type": "number"},
                },
                "required": ["content"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "forget_memory",
            "description": "软删除一条长期记忆。需要 recall 返回的 memory id。",
            "parameters": {
                "type": "object",
                "properties": {
                    "memory_id": {"type": "integer"},
                },
                "required": ["memory_id"],
                "additionalProperties": False,
            },
        },
    },
]

# 仅依赖会话消息，不回调 Java
LOCAL_TOOL_SPECS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_last_advice",
            "description": "获取本会话上一轮助手建议摘要，用于对照验收。",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
]

TOOL_SPECS: list[dict[str, Any]] = JAVA_TOOL_SPECS + LOCAL_TOOL_SPECS

JAVA_TOOL_NAMES = frozenset(
    str(spec["function"]["name"]) for spec in JAVA_TOOL_SPECS
)


class JavaToolClient:
    """编排器侧：把工具调用转发到 Java 执行官。"""

    def __init__(self, settings: Optional[Settings] = None) -> None:
        self.settings = settings or get_settings()

    def _headers(self, user_public_id: str) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "X-Internal-Token": self.settings.internal_tool_token,
            "X-User-Public-Id": user_public_id or "",
        }

    async def exec_tool(
        self,
        *,
        tool_name: str,
        params: Optional[dict[str, Any]] = None,
        session_id: str = "",
        problem_id: Optional[int] = None,
        user_public_id: str = "",
    ) -> dict[str, Any]:
        if tool_name == "get_last_advice":
            raise ValueError("get_last_advice is local-only; do not call Java")
        url = f"{self.settings.business_internal_url.rstrip('/')}/internal/tools/exec"
        payload = {
            "tool_name": tool_name,
            "params": params or {},
            "session_id": session_id,
            "problem_id": problem_id,
        }
        async with httpx.AsyncClient(timeout=self.settings.llm_timeout_seconds) as client:
            resp = await client.post(
                url, json=payload, headers=self._headers(user_public_id)
            )
            resp.raise_for_status()
            data = resp.json()
            return data if isinstance(data, dict) else {"ok": False, "note": "invalid response"}

    def exec_tool_sync(
        self,
        *,
        tool_name: str,
        params: Optional[dict[str, Any]] = None,
        session_id: str = "",
        problem_id: Optional[int] = None,
        user_public_id: str = "",
        history: Optional[list[dict[str, str]]] = None,
    ) -> str:
        """同步入口（LangGraph 同步节点可用）；返回 JSON 字符串。"""
        if tool_name == "get_last_advice":
            advice = ""
            for msg in reversed(history or []):
                if msg.get("role") == "assistant" and msg.get("content"):
                    advice = str(msg["content"])
                    break
            return json.dumps(
                {
                    "ok": bool(advice),
                    "advice": advice,
                    "note": "本地会话历史" if advice else "无历史建议",
                    "executor": "llm-service-local",
                },
                ensure_ascii=False,
            )

        url = f"{self.settings.business_internal_url.rstrip('/')}/internal/tools/exec"
        payload = {
            "tool_name": tool_name,
            "params": params or {},
            "session_id": session_id,
            "problem_id": problem_id,
        }
        with httpx.Client(timeout=self.settings.llm_timeout_seconds) as client:
            resp = client.post(url, json=payload, headers=self._headers(user_public_id))
            resp.raise_for_status()
            data = resp.json()
        return json.dumps(data, ensure_ascii=False)
