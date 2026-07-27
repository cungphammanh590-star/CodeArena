"""每日回顾：聚合事实；Local 念数 / Api 可解读。"""

from __future__ import annotations

from typing import Any


def assemble_daily_facts(profile: dict[str, Any] | None) -> dict[str, Any]:
    profile = profile or {}
    today = dict(profile.get("today") or {})
    return {
        "day": today.get("day") or "",
        "attempts": int(today.get("attempts") or 0),
        "accepted": int(today.get("accepted") or 0),
        "wrong": int(today.get("wrong") or 0),
        "acceptance_rate": float(today.get("acceptance_rate") or 0),
        "slowest_tag": str(today.get("slowest_tag") or ""),
        "weak_tags": list(profile.get("weak_tags") or []),
        "problems": list(today.get("problems") or []),
        "summary_text": str(profile.get("summary_text") or ""),
    }


def format_daily_review_local(facts: dict[str, Any]) -> str:
    """Local：只念数据，不做过度解读。"""
    day = facts.get("day") or "今日"
    attempts = int(facts.get("attempts") or 0)
    accepted = int(facts.get("accepted") or 0)
    wrong = int(facts.get("wrong") or 0)
    rate = int(float(facts.get("acceptance_rate") or 0) * 100)
    lines = [
        f"【{day} 刷题事实】",
        f"- 提交次数：{attempts}",
        f"- 通过（按题日汇总 AC）：{accepted}",
        f"- 错题汇总：{wrong}",
        f"- 正确率：{rate}%",
    ]
    slow = str(facts.get("slowest_tag") or "")
    if slow:
        lines.append(f"- 今日错题偏多标签：{slow}")
    weak = facts.get("weak_tags") or []
    if weak:
        lines.append("- 画像薄弱标签：" + "、".join(weak[:5]))
    problems = list(facts.get("problems") or [])[:8]
    if problems:
        lines.append("- 今日题目：")
        for p in problems:
            lines.append(
                f"  · {p.get('problem_id')}. {p.get('title') or ''} "
                f"（尝试 {p.get('attempts', 0)}，AC {p.get('accepted_today', 0)}，"
                f"错 {p.get('wrong_today', 0)}）"
            )
    if attempts == 0:
        lines.append("今天还没有入库提交。去做几道题后再来回顾。")
    return "\n".join(lines)


def daily_review_api_prompt(facts: dict[str, Any]) -> str:
    body = format_daily_review_local(facts)
    return (
        "根据下列【已聚合事实】写一段简短每日总结（可给 1–2 条练习建议）。"
        "禁止编造事实中不存在的数字或题号。不要输出代码。\n\n"
        f"{body}\n"
    )
