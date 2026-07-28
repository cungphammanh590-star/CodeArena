"""陪练上下文组装。"""

from __future__ import annotations

import json
import re
import sqlite3
from typing import Any, Optional

from leetcode_tracker.coach.strip_comments import strip_code_comments
from leetcode_tracker.kg.queries import format_kg_context_markdown
from leetcode_tracker.core.submissions import (
    count_today_attempts_for_problem,
    get_latest_submission_for_problem,
    get_submission_by_id,
)

_STATUS_HINTS = {
    "Accepted": (
        "✅ 已通过：进入「重构顾问」模式。"
        "只谈可读性/复杂度/常数项；禁止找逻辑错误；禁止给完整重构代码。"
        "用户若问更好写法，先问清是更快、更短还是更易读。"
    ),
    "Wrong Answer": (
        "⚠️ 逻辑错误：重点关注变量更新顺序、哈希表覆盖、返回值语义；"
        "若用下标表达取值约定（值放在哪一格、扫描从 0 还是 1），"
        "核对 nums[i] 与 i/i+1 是否一致，以及最终返回的是下标、值，还是 n+1。"
    ),
    "Runtime Error": (
        "⚠️ 运行时错误：重点关注数组越界（i+1）、空指针（None/null）、递归栈溢出。"
    ),
    "Time Limit Exceeded": (
        "⚠️ 超时：重点关注是否有不必要的嵌套循环、是否可以提前剪枝。"
    ),
    "Compile Error": (
        "⚠️ 编译错误：重点关注语法、类型、未定义变量、括号/缩进是否匹配。"
    ),
}


def _format_tags(tags: Any) -> str:
    if tags is None or tags == "":
        return "（无）"
    if isinstance(tags, (list, tuple)):
        items = [str(x).strip() for x in tags if str(x).strip()]
        return "、".join(items) if items else "（无）"
    raw = str(tags).strip()
    if not raw:
        return "（无）"
    try:
        value = json.loads(raw)
        if isinstance(value, list):
            items = [str(x).strip() for x in value if str(x).strip()]
            return "、".join(items) if items else "（无）"
    except json.JSONDecodeError:
        pass
    return raw


def _format_runtime(runtime_ms: Any) -> str:
    if runtime_ms is None:
        return "—"
    try:
        return f"{int(runtime_ms)} ms"
    except (TypeError, ValueError):
        return "—"


def _format_memory(memory_mb: Any) -> str:
    if memory_mb is None:
        return "—"
    try:
        return f"{float(memory_mb):g} MB"
    except (TypeError, ValueError):
        return "—"


def _code_snippet(
    code: Optional[str], *, language: str = "", max_lines: int = 30
) -> str:
    if not code:
        return ""
    cleaned = strip_code_comments(str(code), language)
    if not cleaned:
        return ""
    lines = cleaned.splitlines()
    snippet = "\n".join(lines[:max_lines])
    if len(lines) > max_lines:
        snippet += "\n... (后续代码省略)"
    return snippet


def refresh_context_code_block(
    markdown: str, code: str, *, language: str = "", max_lines: int = 30
) -> str:
    """用去注释后的代码刷新上下文中的代码围栏（兼容旧 session 缓存）。"""
    md = str(markdown or "")
    if not md or "## 用户当前代码" not in md:
        return md
    snippet = _code_snippet(code, language=language, max_lines=max_lines)
    fence_lang = (language or "text").strip().lower() or "text"
    body = snippet or "（代码未入库，无法展示）"
    replacement = (
        f"## 用户当前代码（仅展示核心逻辑前 {max_lines} 行）\n"
        f"```{fence_lang}\n{body}\n```"
    )
    return re.sub(
        r"## 用户当前代码[^\n]*\n```[^\n]*\n.*?```",
        replacement,
        md,
        count=1,
        flags=re.DOTALL,
    )


def _status_flow(
    conn: sqlite3.Connection, problem_id: int, *, limit: int = 5
) -> str:
    rows = conn.execute(
        """
        SELECT status
        FROM submissions
        WHERE problem_id = ?
        ORDER BY submitted_at DESC, id DESC
        LIMIT ?
        """,
        (problem_id, limit),
    ).fetchall()
    if not rows:
        return "无历史"
    # 查询为新→旧，展示时翻成旧→新
    return " -> ".join(row["status"] for row in reversed(rows))


def _recent_attempts_markdown(
    conn: sqlite3.Connection, problem_id: int, *, limit: int = 5
) -> str:
    rows = conn.execute(
        """
        SELECT status, submitted_at
        FROM submissions
        WHERE problem_id = ?
        ORDER BY submitted_at DESC, id DESC
        LIMIT ?
        """,
        (problem_id, limit),
    ).fetchall()
    if not rows:
        return ""
    items = [f"- {row['submitted_at']}: {row['status']}" for row in rows]
    return "## 该题最近尝试\n" + "\n".join(items)


def build_coach_context(
    conn: sqlite3.Connection,
    submission_id: str = "",
    *,
    problem_id: Optional[int] = None,
) -> dict[str, Any]:
    """构建上下文；精确提交缺失时可显式按题回退到最近提交。"""
    requested_submission_id = str(submission_id or "").strip()
    sub = (
        get_submission_by_id(conn, requested_submission_id)
        if requested_submission_id
        else None
    )
    fallback_used = False
    if sub is None:
        if problem_id is None:
            if requested_submission_id:
                raise ValueError(f"未找到提交: {requested_submission_id}")
            raise ValueError("需要 submission_id 或 problem_id")
        sub = get_latest_submission_for_problem(conn, int(problem_id))
        if sub is None:
            raise ValueError(
                f"未找到提交: {requested_submission_id or '—'}；"
                f"题目 {problem_id} 也没有可回退的历史提交"
            )
        fallback_used = True

    resolved_problem_id = int(sub["problem_id"])
    today_count = count_today_attempts_for_problem(conn, resolved_problem_id)
    kg_md, placement = format_kg_context_markdown(conn, resolved_problem_id)

    title = sub.get("title") or f"Problem {resolved_problem_id}"
    difficulty = sub.get("difficulty") or "—"
    language = sub.get("language") or "text"
    status = str(sub["status"])
    tags_text = _format_tags(sub.get("tags"))
    raw_code = str(sub.get("code") or "")
    code_for_llm = strip_code_comments(raw_code, str(language))
    code_snippet = _code_snippet(raw_code, language=str(language))
    full_code = code_for_llm
    status_flow = _status_flow(conn, resolved_problem_id)
    status_hint = _STATUS_HINTS.get(status, "")
    runtime = _format_runtime(sub.get("runtime_ms"))
    memory = _format_memory(sub.get("memory_mb"))

    fence_lang = str(language).lower()
    submission_md = f"""## 本次提交现场
- 题目：{resolved_problem_id}. {title}（{difficulty}）
- 题目标签：{tags_text}
- 当前状态：**{status}**
- 语言：{sub.get('language') or '—'}
- 运行用时：{runtime}（击败百分比以力扣页面为准，此处不编造）
- 内存消耗：{memory}
- 今日该题已尝试：{today_count} 次
- 该题最近状态流（从旧到新）：{status_flow}

{status_hint}

## 用户当前代码（仅展示核心逻辑前 30 行）
```{fence_lang}
{code_snippet or '（代码未入库，无法展示）'}
```
"""
    recent_md = _recent_attempts_markdown(conn, resolved_problem_id)
    markdown = "\n".join(part for part in (submission_md, recent_md, kg_md) if part)
    resolved_submission_id = str(sub["submission_id"])
    return {
        "submission_id": resolved_submission_id,
        "requested_submission_id": requested_submission_id or resolved_submission_id,
        "resolved_submission_id": resolved_submission_id,
        "fallback_used": fallback_used,
        "problem_id": resolved_problem_id,
        "status": status,
        "title": title,
        "difficulty": difficulty,
        "markdown": markdown,
        "placement": placement,
        "today_count": today_count,
        "current_code": full_code,
        "language": str(language),
    }
