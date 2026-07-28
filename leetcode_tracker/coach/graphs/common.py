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
    from leetcode_tracker.coach.session_sync import is_progress_feedback

    t = (user_text or "").strip()
    if not t or is_progress_feedback(t):
        return []
    if not any(p in t for p in NEGATION_PHRASES):
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
        parts.append("## 已讨论标识符（须仍出现在当前代码中才可引用）\n" + ", ".join(idents[:16]))
    if summary:
        parts.append("## 意见摘要（换码前诊断仅供参考，禁止复述旧代码）\n" + summary)
    return "\n\n".join(parts)


def fold_opinions_on_code_change(
    messages: list[Any],
    *,
    prev_summary: str = "",
    from_status: str = "",
    to_status: str = "",
) -> str:
    """把换码前对话压成意见摘要（保留助手诊断要点，不保留旧码）。"""
    bits: list[str] = []
    for msg in messages or []:
        content = str(getattr(msg, "content", "") or "").strip()
        if not content:
            continue
        name = msg.__class__.__name__
        if "Human" in name:
            if content.startswith("〔") or content in {
                "结束",
                "看思路",
                "今日复习",
                "今日总结",
                "推荐下一题",
            }:
                continue
            bits.append(f"- 用户：{content[:100]}")
        elif "AI" in name:
            if content.startswith("（系统") or "今日复习队列" in content:
                continue
            # 单行意见，去掉过长代码感
            line = content.replace("\n", " ").strip()[:160]
            bits.append(f"- 意见：{line}")
    header = f"【换码 {from_status or '—'}→{to_status or '—'} 前】"
    chunk = "\n".join(bits[-20:])
    prev = (prev_summary or "").strip()
    parts = [p for p in (prev, header, chunk) if p]
    return "\n".join(parts).strip()[:2000]


def filter_idents_in_code(idents: list[str], code: str) -> list[str]:
    src = code or ""
    out: list[str] = []
    for name in idents or []:
        n = str(name).strip()
        if n and n in src and n not in out:
            out.append(n)
    return out[:16]


def messages_after_code_epoch(messages: list[Any]) -> list[Any]:
    """换码后送模：只保留最新一条用户消息，旧对话已进意见摘要。"""
    msgs = list(messages or [])
    for msg in reversed(msgs):
        if "Human" in msg.__class__.__name__:
            return [msg]
    return msgs[-1:] if msgs else []


def close_summary_from_state(state: dict[str, Any]) -> str:
    """结束对话：用意见摘要 / 最近诊断作依据。"""
    summary = str(state.get("context_summary") or "").strip()
    opinion_lines = [
        ln for ln in summary.splitlines() if ln.strip().startswith("- 意见")
    ][-3:]
    if opinion_lines:
        return (
            "好的，今天先到这里。本轮值得再核对的点：\n"
            + "\n".join(opinion_lines)
            + "\n下次提交前对一下这些点。"
        )
    last = str(state.get("last_assistant_text") or "").strip().replace("\n", " ")
    if last:
        return (
            "好的，今天先到这里。记得刚才提到的："
            f"{last[:140]}{'…' if len(last) > 140 else ''}。"
            "下次提交前再对一遍。"
        )
    return (
        "好的，今天先到这里。记得把刚才怀疑的点记下来，"
        "下次提交前再对一遍。"
    )


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
    # Local 常关掉全文 context，但仍需当前代码片段
    already_has_code = include_full_context and "## 用户当前代码" in context_markdown
    if code and not already_has_code:
        snippet = "\n".join(code.splitlines()[:40])
        pieces.append(f"## 用户当前代码（片段）\n```\n{snippet}\n```")
    if bool(state.get("code_epoch_bumped")) or "【换码" in str(
        state.get("context_summary") or ""
    ):
        pieces.append(
            "## 换码说明\n"
            "用户代码/状态已切换为库内最新。"
            "意见摘要仅供参考；禁止复述或假设旧代码内容；"
            "疑点必须引用【用户当前代码】里真实出现的标识符。"
        )
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
