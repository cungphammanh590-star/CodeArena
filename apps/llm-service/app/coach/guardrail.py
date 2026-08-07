"""模型回复软护栏：剥离代码块，避免小模型泄题。"""

from __future__ import annotations

import re

_FENCE_RE = re.compile(r"```[\w+-]*\n.*?```", re.DOTALL)
_INLINE_FENCE_RE = re.compile(r"```.*?```", re.DOTALL)

_STRIP_FOLLOWUP = "不过具体代码我不能直接贴，先回答刚才的方向选择？"


def strip_code_blocks(text: str) -> tuple[str, bool]:
    raw = text or ""
    cleaned, n1 = _FENCE_RE.subn("", raw)
    cleaned, n2 = _INLINE_FENCE_RE.subn("", cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    return cleaned, (n1 + n2) > 0


def apply_code_block_guardrail(reply: str) -> tuple[str, bool]:
    cleaned, stripped = strip_code_blocks(reply)
    if not stripped:
        return cleaned, False
    if cleaned:
        return f"{cleaned}\n{_STRIP_FOLLOWUP}", True
    return _STRIP_FOLLOWUP, True
