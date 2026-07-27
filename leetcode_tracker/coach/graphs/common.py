"""双图共享：结束语、否定抽取、模型调用、checkpoint 连接。"""

from __future__ import annotations

import re
import sqlite3
import threading
from typing import Any

from leetcode_tracker.coach.debug_log import log_llm_turn
from leetcode_tracker.coach.exit_detect import is_vague_user_message
from leetcode_tracker.coach.guardrail import apply_code_block_guardrail
from leetcode_tracker.coach.prompts import system_prompt_for_status
from leetcode_tracker.coach.state import END_PHRASES, NEGATION_PHRASES
from leetcode_tracker.infra.paths import db_path
from leetcode_tracker.llm.provider import build_chat_model


class GenerationCancelled(Exception):
    """客户端断开后停止消费模型流。"""


def is_done_message(text: str) -> bool:
    t = text.strip().lower()
    return any(p in t for p in END_PHRASES)


def extract_negations(user_text: str, last_assistant: str) -> list[str]:
    t = (user_text or "").strip()
    if not t or not any(p in t for p in NEGATION_PHRASES):
        return []
    snippet = (last_assistant or "").strip().split("\n")[0][:80]
    if not snippet:
        snippet = t[:60]
    return [f"用户否定：{snippet}"]


def append_unique(items: list[str], extra: list[str], *, limit: int = 12) -> list[str]:
    out = list(items or [])
    for x in extra:
        x = str(x).strip()
        if x and x not in out:
            out.append(x)
    return out[-limit:]


def extract_identifiers(text: str) -> list[str]:
    found = re.findall(r"\b[a-zA-Z_][a-zA-Z0-9_]{1,24}\b", text or "")
    stop = {
        "if",
        "else",
        "for",
        "while",
        "return",
        "int",
        "str",
        "list",
        "None",
        "true",
        "false",
        "self",
        "def",
        "class",
    }
    out: list[str] = []
    for name in found:
        if name in stop or name.lower() in stop:
            continue
        if name not in out:
            out.append(name)
        if len(out) >= 16:
            break
    return out


def rejected_block(state: dict[str, Any]) -> str:
    rejected = list(state.get("rejected_suspicions") or [])
    idents = list(state.get("mentioned_identifiers") or [])
    summary = str(state.get("context_summary") or "").strip()
    parts: list[str] = []
    if rejected:
        parts.append(
            "## 用户已否定的疑点（禁止再当作首选）\n"
            + "\n".join(f"- {x}" for x in rejected)
        )
    if idents:
        parts.append("## 已讨论标识符\n" + ", ".join(idents[:16]))
    if summary:
        parts.append("## 更早轮次摘要\n" + summary)
    return "\n\n".join(parts)


def build_system_content(
    state: dict[str, Any],
    *,
    extra: str = "",
    include_full_context: bool = True,
) -> str:
    from leetcode_tracker.coach.profile import profile_prompt_block

    status = str(state.get("submission_status") or "")
    prompt = system_prompt_for_status(status)
    context_markdown = str(state.get("context_markdown") or "")
    block = rejected_block(state)
    profile_block = profile_prompt_block(state.get("user_profile"))
    pieces = [prompt]
    if profile_block:
        pieces.append(profile_block)
    if include_full_context and context_markdown:
        pieces.append(f"## 陪练上下文\n{context_markdown}")
    code = str(state.get("current_code") or "").strip()
    if code and "## 用户当前代码" not in context_markdown:
        snippet = "\n".join(code.splitlines()[:40])
        pieces.append(f"## 用户当前代码（片段）\n```\n{snippet}\n```")
    if block:
        pieces.append(block)
    if extra:
        pieces.append(extra)
    return "\n\n".join(pieces)


def trim_messages_for_local(messages: list[Any], *, keep_pairs: int = 2) -> list[Any]:
    """Local 送模：只保留最近 keep_pairs 轮人类/助手（约 2*keep_pairs 条）+ 可选开头。"""
    msgs = list(messages or [])
    if len(msgs) <= keep_pairs * 2 + 1:
        return msgs
    return msgs[-(keep_pairs * 2) :]


def stream_model_reply(
    *,
    outbound: list[Any],
    cancel_event: threading.Event,
    session_id: str,
    thread_id: str,
    meta: dict[str, Any],
) -> tuple[str, bool]:
    model = build_chat_model()
    accumulated = ""
    for chunk in model.stream(outbound):
        if cancel_event.is_set():
            raise GenerationCancelled()
        piece = getattr(chunk, "content", None)
        if not piece:
            continue
        text = piece if isinstance(piece, str) else str(piece)
        if text:
            accumulated += text
    if not accumulated:
        raise RuntimeError("模型未返回内容")
    reply, stripped = apply_code_block_guardrail(accumulated)
    log_llm_turn(
        session_id=session_id,
        thread_id=thread_id,
        messages=outbound,
        reply=reply,
        meta={**meta, "stripped": stripped},
    )
    return reply, stripped


def open_checkpoint_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path()), check_same_thread=False, timeout=5.0)
    conn.execute("PRAGMA busy_timeout = 5000")
    return conn


def last_human_text(messages: list[Any]) -> str:
    for msg in reversed(messages or []):
        if "Human" in msg.__class__.__name__:
            return str(getattr(msg, "content", "") or "")
    return ""


def update_vague_counter(state: dict[str, Any], user_text: str) -> int:
    n = int(state.get("consecutive_vague") or 0)
    if is_vague_user_message(user_text):
        return n + 1
    return 0


def fallback_local_text(turn: int) -> str:
    replies = (
        "模型暂时不可用，我们先不看答案。你能说出这次最小的失败用例，以及实际结果和预期结果分别是什么吗？",
        "先沿着你的思路排查：你认为哪个不变量应该始终成立？请挑一次循环或一次递归调用验证它。",
        "把问题再缩小一点：边界、状态转移和数据范围中，你现在最不确定哪一项？",
        "暂时不用改代码。请先用一句话说明当前做法为什么应该成立，再找一个能推翻这句话的输入。",
    )
    return replies[max(0, turn) % len(replies)]


def close_checkpoint_graph(graph: Any) -> None:
    conn = getattr(graph, "_leetcode_checkpoint_conn", None)
    if conn is not None:
        conn.close()
