"""B/C 档回复政策。"""

from __future__ import annotations

import re

from app.coach.guardrail import apply_code_block_guardrail, strip_code_blocks

_MAX_CODE_LINES = 10


def build_refuse_nudge(*, status_line: str, cta: str, short: bool = False) -> str:
    if short:
        return f"还是先回到刷题吧。{cta}".strip()
    status = (status_line or "").strip() or "今天的刷题记录还不多。"
    cta = (cta or "").strip() or "要不要先看一道未通过的题，或让我按薄弱点推荐一题？"
    return (
        "我主要陪你刷题和复盘，这个话题先跳过。\n"
        f"{status}\n"
        f"{cta}"
    )


def apply_smart_reply_policy(
    reply: str,
    *,
    allow_code_原文: bool,
) -> tuple[str, bool]:
    raw = reply or ""
    if not allow_code_原文:
        return apply_code_block_guardrail(raw)

    cleaned, stripped = strip_code_blocks(raw)
    fences = re.findall(r"```[\w+-]*\n(.*?)```", raw, flags=re.DOTALL)
    if not fences:
        return cleaned, stripped

    snippets: list[str] = []
    for body in fences:
        lines = [ln for ln in body.splitlines() if ln.strip() or ln == ""]
        if len(lines) > _MAX_CODE_LINES:
            lines = lines[:_MAX_CODE_LINES] + ["# …其余请自己补全，我不能给完整可运行解法"]
        joined = "\n".join(lines)
        if (
            re.search(r"^\s*(def|class)\s+", joined, re.M)
            and re.search(r"\breturn\b", joined)
            and len(lines) >= 8
        ):
            lines = lines[:5] + ["# …此处省略，请按思路自己补全函数"]
            joined = "\n".join(lines)
        snippets.append(joined)

    parts = [cleaned] if cleaned else []
    for snip in snippets:
        parts.append("参考语句（非完整题解）：\n" + snip)
    parts.append("完整可运行解法需要你自己拼起来；需要的话我可以继续只讲下一步思路。")
    return "\n\n".join(p for p in parts if p).strip(), True
