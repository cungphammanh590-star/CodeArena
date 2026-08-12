"""消息窗口与滚动摘要（按近似 token 裁剪）。"""

from __future__ import annotations

from typing import Any

from app.coach.state import MESSAGE_WINDOW

# 近似：4 字符 ≈ 1 token（中英混合够用；无需强制 tiktoken）
DEFAULT_TOKEN_BUDGET = 8000


def estimate_tokens(text: str) -> int:
    if not text:
        return 0
    return max(1, (len(text) + 3) // 4)


def message_tokens(msg: Any) -> int:
    content = getattr(msg, "content", "") or ""
    if isinstance(content, list):
        content = "".join(
            str(p.get("text") if isinstance(p, dict) else p) for p in content
        )
    return estimate_tokens(str(content))


def _cls(msg: Any) -> str:
    return msg.__class__.__name__ if msg is not None else ""


def _is_ai(msg: Any) -> bool:
    name = _cls(msg)
    return "AIMessage" in name or (name.startswith("AI") and "Tool" not in name)


def _is_tool(msg: Any) -> bool:
    return "ToolMessage" in _cls(msg) or _cls(msg) == "ToolMessageChunk"


def _is_human(msg: Any) -> bool:
    return "Human" in _cls(msg)


def _tool_call_id(tc: Any) -> str:
    if isinstance(tc, dict):
        return str(tc.get("id") or "").strip()
    return str(getattr(tc, "id", "") or "").strip()


def _ai_content_only(msg: Any) -> Any:
    """去掉 tool_calls，仅保留正文，避免半截工具往返进模型。"""
    from langchain_core.messages import AIMessage

    content = getattr(msg, "content", "") or ""
    if isinstance(content, list):
        content = "".join(
            str(p.get("text") if isinstance(p, dict) else p) for p in content
        )
    return AIMessage(content=str(content))


def sanitize_messages_for_llm(messages: list[Any]) -> list[Any]:
    """保证 tool 消息成对：孤儿 ToolMessage / 未完成的 tool_calls 不会进模型。

    OpenAI 要求：role=tool 必须紧跟带 tool_calls 的 assistant。
    裁窗、取消、ask_user 恢复都可能破坏配对；此处做防御性修复。
    """
    if not messages:
        return []

    out: list[Any] = []
    i = 0
    n = len(messages)
    while i < n:
        m = messages[i]
        if _is_tool(m):
            # 前无对应 AI(tool_calls) → 丢弃
            i += 1
            continue
        if _is_ai(m):
            tool_calls = list(getattr(m, "tool_calls", None) or [])
            if not tool_calls:
                out.append(m)
                i += 1
                continue

            expected = {_tool_call_id(tc) for tc in tool_calls}
            expected.discard("")
            j = i + 1
            tool_msgs: list[Any] = []
            found: set[str] = set()
            while j < n and _is_tool(messages[j]):
                tid = str(getattr(messages[j], "tool_call_id", "") or "").strip()
                if tid and tid in expected and tid not in found:
                    tool_msgs.append(messages[j])
                    found.add(tid)
                j += 1

            if expected and found == expected:
                out.append(m)
                out.extend(tool_msgs)
            else:
                # 半截工具往返：只保留正文（若有），丢掉残缺 tool 结果
                plain = _ai_content_only(m)
                if str(getattr(plain, "content", "") or "").strip():
                    out.append(plain)
            i = j
            continue

        out.append(m)
        i += 1
    return out


def ensure_tool_call_prelude(
    messages: list[Any],
    *,
    tool_call_id: str,
    tool_name: str = "ask_user",
) -> list[Any]:
    """在追加 ToolMessage 前，确保存在带匹配 tool_calls 的 AI 消息。"""
    from langchain_core.messages import AIMessage

    tid = (tool_call_id or "").strip() or "ask_user"
    msgs = list(messages)
    # 从尾部找最近一条 AI（跳过尾部 Human 由调用方处理）
    for m in reversed(msgs):
        if _is_human(m):
            continue
        if _is_tool(m):
            continue
        if _is_ai(m):
            ids = {_tool_call_id(tc) for tc in (getattr(m, "tool_calls", None) or [])}
            if tid in ids:
                return msgs
            break
        break
    msgs.append(
        AIMessage(
            content="",
            tool_calls=[{"id": tid, "name": tool_name or "ask_user", "args": {}}],
        )
    )
    return msgs


def trim_messages(
    messages: list[Any],
    *,
    window: int = MESSAGE_WINDOW,
    token_budget: int = DEFAULT_TOKEN_BUDGET,
) -> list[Any]:
    """先按条数上限，再按 token 预算从尾部保留，最后做 tool 配对消毒。"""
    if not messages:
        return []
    msgs = list(messages[-window:]) if len(messages) > window else list(messages)
    total = 0
    kept: list[Any] = []
    for m in reversed(msgs):
        t = message_tokens(m)
        if kept and total + t > token_budget:
            break
        kept.append(m)
        total += t
    kept.reverse()
    return sanitize_messages_for_llm(kept)


def build_summary_line(
    *,
    old_summary: str,
    phase: str,
    intent: str,
    topic: str,
    problem_id: int,
    reply: str,
) -> str:
    """轻量规则摘要（不调 LLM）；persist 节点可再 remember。"""
    bits = []
    if topic:
        bits.append(f"专题={topic}")
    if problem_id > 0:
        bits.append(f"题={problem_id}")
    bits.append(f"phase={phase}")
    bits.append(f"intent={intent}")
    snippet = (reply or "").replace("\n", " ").strip()
    if len(snippet) > 80:
        snippet = snippet[:80] + "…"
    if snippet:
        bits.append(f"末回复:{snippet}")
    line = "；".join(bits)
    prev = (old_summary or "").strip()
    if not prev:
        return line
    parts = [p for p in prev.split(" || ") if p.strip()]
    parts.append(line)
    return " || ".join(parts[-2:])


def should_run_digest(
    *,
    turn_count: int,
    close_scope: str,
    force: bool,
    every_n: int,
) -> bool:
    """remember / 强摘要时机（L2 落库每回合都会做，与此无关）。"""
    if force:
        return True
    if close_scope in {"problem_segment", "session"}:
        return True
    if turn_count > 0 and every_n > 0 and turn_count % every_n == 0:
        return True
    return False


def emit_text_chunks(writer, text: str, *, chunk_size: int = 24) -> None:
    """将整段文本拆成多段 token 事件，改善 SSE UX。"""
    raw = text or ""
    if not raw:
        return
    if len(raw) <= chunk_size:
        writer({"type": "token", "text": raw})
        return
    for i in range(0, len(raw), chunk_size):
        writer({"type": "token", "text": raw[i : i + chunk_size]})
