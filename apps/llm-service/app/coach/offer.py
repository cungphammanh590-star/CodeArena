"""空闲提议：经 Java 工具回调，不直连数据库。"""

from __future__ import annotations

from typing import Any, Optional

from app.services.tool_client import JavaToolClient


def problem_url(slug: str, problem_id: int) -> str:
    slug = (slug or "").strip()
    if slug:
        return f"https://leetcode.cn/problems/{slug}/"
    return f"/problems/{problem_id}"


def status_one_liner(profile: dict[str, Any] | None) -> str:
    p = profile or {}
    today = p.get("today") or {}
    submitted = int(
        today.get("submissions")
        or today.get("submit_count")
        or p.get("submission_count")
        or 0
    )
    due = int(p.get("review_due_count") or 0)
    weak = list(p.get("weak_tags") or [])[:2]
    bits = []
    if submitted <= 0:
        bits.append("你今天还没提交")
    else:
        bits.append(f"你今天已提交 {submitted} 次")
    if due:
        bits.append(f"有 {due} 题到期复习")
    if weak:
        bits.append("薄弱方向：" + "、".join(str(x) for x in weak))
    mastered = p.get("mastered_count")
    if mastered is not None:
        bits.append(f"已掌握 {mastered} 题")
    return "；".join(bits) + "。" if bits else "可以先从一题简单的开刷。"


def build_offer_payload(
    tools: JavaToolClient,
    *,
    session_id: str,
    user_public_id: str,
    problem_id: Optional[int] = None,
) -> dict[str, Any]:
    """空闲提议：有未通过 → continue；否则 suggest_next_problems。"""
    raw = tools.exec_tool_sync(
        tool_name="list_unpassed_problems",
        session_id=session_id,
        problem_id=problem_id,
        user_public_id=user_public_id,
    )
    import json

    data = json.loads(raw) if isinstance(raw, str) else raw
    items = list(data.get("items") or []) if isinstance(data, dict) else []
    if items:
        top = items[0]
        pid = int(top.get("problem_id") or 0)
        title = str(top.get("title") or pid)
        status = str(top.get("status") or top.get("last_status") or "未 AC")
        url = str(top.get("url") or problem_url(str(top.get("slug") or ""), pid))
        return {
            "kind": "continue",
            "problem": top,
            "alternatives": items[1:],
            "cta": (
                f"你还有未通过的题：{pid}. {title} （{status}）。\n"
                f"题页：{url}\n"
                "要继续讨论这题，还是先自己去网页交一版？"
            ),
        }

    sug_raw = tools.exec_tool_sync(
        tool_name="suggest_next_problems",
        params={"limit": 3},
        session_id=session_id,
        problem_id=problem_id,
        user_public_id=user_public_id,
    )
    sug = json.loads(sug_raw) if isinstance(sug_raw, str) else sug_raw
    cands = list((sug or {}).get("candidates") or []) if isinstance(sug, dict) else []
    if not cands:
        return {
            "kind": "none",
            "cta": "题库里暂时没有清晰的下一题候选。你可以报题号，我们直接开聊。",
        }
    lines = ["最近没有未通过的题可续，按规则给你几道候选："]
    for i, c in enumerate(cands, 1):
        pid = int(c.get("problem_id") or c.get("id") or 0)
        title = str(c.get("title") or pid)
        reason = f"（{c['reason']}）" if c.get("reason") else ""
        url = str(c.get("url") or problem_url(str(c.get("slug") or ""), pid))
        lines.append(f"{i}. {pid}. {title} {reason}\n   {url}")
    lines.append("想刷哪一题？回题号即可，我帮你绑定后继续。")
    return {"kind": "recommend", "candidates": cands, "cta": "\n".join(lines)}
