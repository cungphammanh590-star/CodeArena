"""LangGraph tool specs + Java executor callback client.

LLM 只决策 tool_name/params；数据访问一律回调 business-service
``POST /internal/tools/exec``（编排器-执行器）。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Optional

import httpx

from app.config import Settings, get_settings
from app.observability.logging_setup import log_extra
from app.observability.request_context import get_request_id
from app.observability.skywalking_agent import tool_span

logger = logging.getLogger(__name__)

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
            "name": "generate_study_plan",
            "description": (
                "按用户目标生成刷题题单，并可排多日日程。"
                "goal_type=company|topic|list；goal_ref=公司名/专题名/题单id（如 Google、动态规划、hot100）。"
                "用户说了天数则传 days 且 schedule=true；只要题单则 schedule=false。"
                "不要自行编造长题号列表。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "goal_type": {
                        "type": "string",
                        "description": "company | topic | list",
                    },
                    "goal_ref": {
                        "type": "string",
                        "description": "目标引用：Google / 动态规划 / hot100 等",
                    },
                    "title": {"type": "string", "description": "可选题单标题"},
                    "days": {"type": "integer", "description": "日程天数 7～90"},
                    "daily_goal": {"type": "integer", "description": "每日题量 2～5"},
                    "schedule": {
                        "type": "boolean",
                        "description": "是否排多日日程；默认有 days 则为 true",
                    },
                    "difficulty": {
                        "type": "string",
                        "description": "可选 Easy|Medium|Hard|mixed",
                    },
                    "limit": {"type": "integer", "description": "题池上限"},
                },
                "required": ["goal_type", "goal_ref"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_today_tasks",
            "description": "查询当前用户今日刷题计划任务列表。",
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
            "name": "get_active_plan",
            "description": "查询进行中的刷题计划摘要（goal、剩余天数、今日题量）。",
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

# 仅依赖会话消息 / 本地沙箱，不回调 Java
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
    {
        "type": "function",
        "function": {
            "name": "code_execution",
            "description": (
                "在安全沙箱中运行短代码片段以验证思路或样例。"
                "仅支持 python；禁止输出完整可提交题解；不要读取库内 AC 源码。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "language": {
                        "type": "string",
                        "description": "python（P0）",
                    },
                    "code": {"type": "string", "description": "要执行的源码"},
                    "stdin": {"type": "string", "description": "可选标准输入"},
                    "timeout": {
                        "type": "integer",
                        "description": "超时秒数 1～60，默认 10",
                    },
                },
                "required": ["language", "code"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "solve_plan",
            "description": (
                "题内跟练：先给出简短 analysis 与有序步骤（2～6 步），再逐步辅导。"
                "步骤 id 由系统生成（S1…）。未 plan 前不要长篇题解。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "analysis": {
                        "type": "string",
                        "description": "一两句：题意与思路",
                    },
                    "steps": {
                        "type": "array",
                        "description": "有序步骤，每项 {goal}",
                        "items": {
                            "type": "object",
                            "properties": {"goal": {"type": "string"}},
                            "required": ["goal"],
                        },
                    },
                },
                "required": ["analysis", "steps"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "solve_finish_step",
            "description": "标记当前步骤完成并推进；传入 step_id 与短摘要。",
            "parameters": {
                "type": "object",
                "properties": {
                    "step_id": {"type": "string", "description": "如 S1"},
                    "summary": {"type": "string", "description": "该步结论摘要"},
                },
                "required": ["step_id", "summary"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "solve_replan",
            "description": "因卡点替换计划步骤；同一会话最多 2 次。",
            "parameters": {
                "type": "object",
                "properties": {
                    "reason": {"type": "string"},
                    "steps": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {"goal": {"type": "string"}},
                            "required": ["goal"],
                        },
                    },
                },
                "required": ["reason", "steps"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ask_user",
            "description": (
                "需要澄清关键约束时调用（1～4 题，可选项/自由文本）。"
                "调用后本回合暂停，等待前端 submit_user_reply；不要猜测缺失信息。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "intro": {"type": "string"},
                    "questions": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": "string"},
                                "prompt": {"type": "string"},
                                "header": {"type": "string"},
                                "options": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "label": {"type": "string"},
                                            "description": {"type": "string"},
                                        },
                                    },
                                },
                                "multi_select": {"type": "boolean"},
                                "allow_free_text": {"type": "boolean"},
                                "placeholder": {"type": "string"},
                            },
                            "required": ["prompt"],
                        },
                    },
                },
                "required": ["questions"],
                "additionalProperties": False,
            },
        },
    },
]

TOOL_SPECS: list[dict[str, Any]] = JAVA_TOOL_SPECS + LOCAL_TOOL_SPECS

JAVA_TOOL_NAMES = frozenset(
    str(spec["function"]["name"]) for spec in JAVA_TOOL_SPECS
)
LOCAL_TOOL_NAMES = frozenset(
    str(spec["function"]["name"]) for spec in LOCAL_TOOL_SPECS
)


class JavaToolClient:
    """编排器侧：把工具调用转发到 Java 执行官。"""

    def __init__(self, settings: Optional[Settings] = None) -> None:
        self.settings = settings or get_settings()

    def _headers(self, user_public_id: str) -> dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "X-Internal-Token": self.settings.internal_tool_token,
            "X-User-Public-Id": user_public_id or "",
        }
        rid = get_request_id()
        if rid:
            headers["X-Request-Id"] = rid
        return headers

    async def exec_tool(
        self,
        *,
        tool_name: str,
        params: Optional[dict[str, Any]] = None,
        session_id: str = "",
        problem_id: Optional[int] = None,
        user_public_id: str = "",
    ) -> dict[str, Any]:
        if tool_name in LOCAL_TOOL_NAMES:
            raise ValueError(f"{tool_name} is local-only; do not call Java")
        url = f"{self.settings.business_internal_url.rstrip('/')}/internal/tools/exec"
        payload = {
            "tool_name": tool_name,
            "params": params or {},
            "session_id": session_id,
            "problem_id": problem_id,
        }
        t0 = time.perf_counter()
        with tool_span(tool_name, session_id=session_id):
            try:
                async with httpx.AsyncClient(
                    timeout=self.settings.llm_timeout_seconds,
                    trust_env=False,
                ) as client:
                    resp = await client.post(
                        url, json=payload, headers=self._headers(user_public_id)
                    )
                    resp.raise_for_status()
                    data = resp.json()
                dur = (time.perf_counter() - t0) * 1000
                logger.info(
                    "tool ok name=%s",
                    tool_name,
                    extra=log_extra(
                        request_id=get_request_id(),
                        tool_name=tool_name,
                        session_id=session_id,
                        duration_ms=dur,
                    ),
                )
                return data if isinstance(data, dict) else {"ok": False, "note": "invalid response"}
            except Exception:
                dur = (time.perf_counter() - t0) * 1000
                logger.exception(
                    "tool failed name=%s",
                    tool_name,
                    extra=log_extra(
                        request_id=get_request_id(),
                        tool_name=tool_name,
                        session_id=session_id,
                        duration_ms=dur,
                    ),
                )
                raise

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
        if tool_name in LOCAL_TOOL_NAMES:
            from app.coach.local_tools import execute_local_tool

            local = execute_local_tool(
                tool_name=tool_name,
                params=params,
                state={
                    "session_id": session_id,
                    "user_public_id": user_public_id,
                    "problem_id": problem_id,
                },
                history=history or [],
            )
            if local is not None:
                return local.content
            raise ValueError(f"local tool {tool_name} failed to execute")

        url = f"{self.settings.business_internal_url.rstrip('/')}/internal/tools/exec"
        payload = {
            "tool_name": tool_name,
            "params": params or {},
            "session_id": session_id,
            "problem_id": problem_id,
        }
        t0 = time.perf_counter()
        with tool_span(tool_name, session_id=session_id):
            try:
                with httpx.Client(
                    timeout=self.settings.llm_timeout_seconds,
                    trust_env=False,
                ) as client:
                    resp = client.post(
                        url, json=payload, headers=self._headers(user_public_id)
                    )
                    resp.raise_for_status()
                    data = resp.json()
                dur = (time.perf_counter() - t0) * 1000
                logger.info(
                    "tool ok name=%s",
                    tool_name,
                    extra=log_extra(
                        request_id=get_request_id(),
                        tool_name=tool_name,
                        session_id=session_id,
                        duration_ms=dur,
                    ),
                )
                return json.dumps(data, ensure_ascii=False)
            except Exception:
                dur = (time.perf_counter() - t0) * 1000
                logger.exception(
                    "tool failed name=%s",
                    tool_name,
                    extra=log_extra(
                        request_id=get_request_id(),
                        tool_name=tool_name,
                        session_id=session_id,
                        duration_ms=dur,
                    ),
                )
                raise
