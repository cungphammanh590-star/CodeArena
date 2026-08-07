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


def trim_messages(
    messages: list[Any],
    *,
    window: int = MESSAGE_WINDOW,
    token_budget: int = DEFAULT_TOKEN_BUDGET,
) -> list[Any]:
    """先按条数上限，再按 token 预算从尾部保留。"""
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
    return kept


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
