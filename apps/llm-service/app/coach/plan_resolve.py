"""plan_resolve：计划线解析节点 = 规则/Java/目录 + 未命中时独立 LLM 补齐。

流水线：
1. 抽题号 / 天数（规则）
2. Java resolve_problem_refs
3. 本地 lc_catalog 补齐
4. 仍有 unmatched → 单独一轮结构化 LLM（不进主对话 messages）
5. 写入 plan_draft；未匹配不阻断
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Optional

from app.coach.intent_smart import is_short_affirmation
from app.coach.lc_catalog import lookup_by_id, lookup_by_title

logger = logging.getLogger(__name__)

_LC_TOKEN = re.compile(
    r"(?i)(?:lc\s*)?(\d{1,4})(?:\s*[.、:：)\-]\s*|\s+|$)",
)
_DAYS = re.compile(r"(\d+)\s*天")
_DAILY = re.compile(r"每天\s*(\d+)\s*题|每日\s*(\d+)|一天\s*(\d+)\s*题")
_LISTISH_MARKERS = (
    "题单",
    "刷题计划",
    "高频",
    "hot 100",
    "hot100",
    "leetcode",
    "力扣",
    "lc ",
    "字节",
    "面试",
)

_LLM_MATCH_SYSTEM = """你是力扣（LeetCode）题号解析器。根据用户给出的题号/中文或英文题名，输出 JSON 数组。
规则：
1. 只输出 JSON 数组，不要 markdown，不要解释。
2. 每项字段：query, problem_id(整数), title(中文优先), slug, confidence(0~1)。
3. 不确定就不要编造：宁可省略该项，也不要瞎猜题号。
4. confidence < 0.75 的不要输出。
5. 最多输出 40 项。
示例：[{"query":"反转链表","problem_id":206,"title":"反转链表","slug":"reverse-linked-list","confidence":0.95}]"""


def latest_user_text(messages: list[Any]) -> str:
    for m in reversed(messages or []):
        if "Human" in m.__class__.__name__:
            return str(getattr(m, "content", "") or "")
    return ""


def extract_lc_ids(text: str) -> list[int]:
    ids: list[int] = []
    seen: set[int] = set()
    for m in _LC_TOKEN.finditer(text or ""):
        try:
            pid = int(m.group(1))
        except ValueError:
            continue
        if pid <= 0 or pid > 3000:
            continue
        if pid in seen:
            continue
        seen.add(pid)
        ids.append(pid)
    return ids


def looks_like_problem_list(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    ids = extract_lc_ids(t)
    if len(ids) >= 3:
        return True
    if len(ids) >= 1 and any(p in t.lower() for p in _LISTISH_MARKERS):
        return True
    if len(ids) >= 1 and t.count("\n") >= 3:
        return True
    return False


def extract_days(text: str) -> Optional[int]:
    m = _DAYS.search(text or "")
    if not m:
        return None
    try:
        return int(m.group(1))
    except ValueError:
        return None


def extract_daily_goal(text: str) -> Optional[int]:
    m = _DAILY.search(text or "")
    if not m:
        return None
    for g in m.groups():
        if g:
            try:
                return int(g)
            except ValueError:
                return None
    return None


def should_run_plan_resolve(state: dict[str, Any]) -> bool:
    """classify 之后：计划意图 + 像题单的用户输入 → 进 plan_resolve。"""
    route = str(state.get("route") or "")
    if route != "agent":
        return False
    intent = str(state.get("intent") or "")
    phase = str(state.get("phase") or "")
    pending = state.get("pending_followup")
    pending_action = ""
    if isinstance(pending, dict):
        pending_action = str(pending.get("action") or "")

    text = latest_user_text(list(state.get("messages") or []))
    if is_short_affirmation(text) and (
        pending_action in {"confirm_plan", "show_today_tasks"}
        or state.get("plan_draft")
    ):
        return False

    if intent in {"plan_create", "plan_adjust"} or phase == "plan_active":
        if looks_like_problem_list(text):
            return True
        if intent == "plan_create" and extract_days(text):
            return True
    return False


def _parse_tool_json(raw: str) -> dict[str, Any]:
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except (json.JSONDecodeError, TypeError):
        return {}


def _extract_json_array(text: str) -> list[Any]:
    raw = (text or "").strip()
    if not raw:
        return []
    if raw.startswith("```"):
        raw = re.sub(r"^```(?:json)?\s*", "", raw)
        raw = re.sub(r"\s*```$", "", raw)
    try:
        data = json.loads(raw)
        return data if isinstance(data, list) else []
    except json.JSONDecodeError:
        m = re.search(r"\[[\s\S]*\]", raw)
        if not m:
            return []
        try:
            data = json.loads(m.group(0))
            return data if isinstance(data, list) else []
        except json.JSONDecodeError:
            return []


def llm_match_unmatched(
    *,
    unmatched: list[dict[str, Any]],
    user_public_id: str,
    context_snippet: str = "",
) -> list[dict[str, Any]]:
    """独立一轮结构化 LLM：只解析仍未命中的 query，不写入主对话。"""
    queries = [
        str(u.get("query") or "").strip()
        for u in unmatched
        if isinstance(u, dict) and str(u.get("query") or "").strip()
    ]
    seen: set[str] = set()
    uniq: list[str] = []
    for q in queries:
        if q in seen:
            continue
        seen.add(q)
        uniq.append(q)
        if len(uniq) >= 40:
            break
    if not uniq:
        return []

    try:
        from langchain_core.messages import HumanMessage, SystemMessage

        from app.services.llm_provider import build_chat_model, fetch_user_llm_settings

        llm = fetch_user_llm_settings(user_public_id=user_public_id or "")
        model = build_chat_model(llm)
        payload = {
            "queries": uniq,
            "hint": (context_snippet or "")[:600],
        }
        resp = model.invoke(
            [
                SystemMessage(content=_LLM_MATCH_SYSTEM),
                HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
            ]
        )
        content = getattr(resp, "content", "") or ""
        if isinstance(content, list):
            content = "".join(
                str(p.get("text") if isinstance(p, dict) else p) for p in content
            )
        rows = _extract_json_array(str(content))
    except Exception as exc:  # noqa: BLE001
        logger.warning("plan_resolve llm_match failed: %s", exc)
        return []

    out: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            pid = int(row.get("problem_id"))
            conf = float(row.get("confidence") or 0)
        except (TypeError, ValueError):
            continue
        if pid <= 0 or pid > 4000 or conf < 0.75:
            continue
        title = str(row.get("title") or "").strip() or f"LC {pid}"
        slug = str(row.get("slug") or "").strip() or f"problem-{pid}"
        cat = lookup_by_id(pid)
        if cat:
            title = cat["title"]
            slug = cat["slug"]
        out.append(
            {
                "problem_id": pid,
                "title": title,
                "slug": slug,
                "difficulty": (cat or {}).get("difficulty") or "Medium",
                "query": str(row.get("query") or title),
                "from_llm": True,
                "confidence": conf,
                "accepted": False,
                "mastered": False,
                "done": False,
            }
        )
    return out


def enrich_with_catalog(resolve_data: dict[str, Any]) -> dict[str, Any]:
    """用本地目录补齐 unmatched 中的纯题号 / 标题。"""
    matched = list(resolve_data.get("matched") or [])
    unmatched = list(resolve_data.get("unmatched") or [])
    ambiguous = list(resolve_data.get("ambiguous") or [])
    problem_ids: list[int] = []
    for m in matched:
        try:
            problem_ids.append(int(m.get("problem_id")))
        except (TypeError, ValueError):
            pass
    id_set = set(problem_ids)

    still_unmatched: list[dict[str, Any]] = []
    catalog_hits = 0
    for row in unmatched:
        q = str((row or {}).get("query") or "").strip()
        hit = None
        if re.fullmatch(r"(?i)(?:lc\s*)?\d{1,4}", q):
            num = int(re.sub(r"(?i)lc\s*", "", q))
            hit = lookup_by_id(num)
        if hit is None:
            hit = lookup_by_title(q)
        if hit and hit["problem_id"] not in id_set:
            entry = {
                "problem_id": hit["problem_id"],
                "title": hit["title"],
                "slug": hit["slug"],
                "difficulty": hit["difficulty"],
                "query": q,
                "from_catalog": True,
                "accepted": False,
                "mastered": False,
                "done": False,
            }
            matched.append(entry)
            id_set.add(hit["problem_id"])
            problem_ids.append(hit["problem_id"])
            catalog_hits += 1
        else:
            still_unmatched.append(row if isinstance(row, dict) else {"query": q})

    remaining = list(resolve_data.get("remaining_ids") or [])
    rem_set: set[int] = set()
    for x in remaining:
        try:
            rem_set.add(int(x))
        except (TypeError, ValueError):
            pass
    for m in matched:
        if m.get("from_catalog") and not m.get("done"):
            try:
                rem_set.add(int(m["problem_id"]))
            except (TypeError, ValueError, KeyError):
                pass
    for m in matched:
        try:
            pid = int(m["problem_id"])
        except (TypeError, ValueError, KeyError):
            continue
        if m.get("done"):
            rem_set.discard(pid)
        elif pid not in rem_set and not m.get("accepted") and not m.get("mastered"):
            rem_set.add(pid)

    passed = [pid for pid in problem_ids if pid not in rem_set]
    out = dict(resolve_data)
    out["matched"] = matched
    out["unmatched"] = still_unmatched
    out["ambiguous"] = ambiguous
    out["problem_ids"] = problem_ids
    out["remaining_ids"] = list(rem_set)
    out["passed_ids"] = passed
    out["matched_count"] = len(problem_ids)
    out["remaining_count"] = len(rem_set)
    out["passed_count"] = len(passed)
    out["catalog_enriched"] = catalog_hits
    return out


def merge_llm_hits(resolve_data: dict[str, Any], hits: list[dict[str, Any]]) -> dict[str, Any]:
    if not hits:
        return resolve_data
    matched = list(resolve_data.get("matched") or [])
    unmatched = list(resolve_data.get("unmatched") or [])
    id_set: set[int] = set()
    for m in matched:
        try:
            id_set.add(int(m.get("problem_id")))
        except (TypeError, ValueError):
            pass
    rem_set: set[int] = set()
    for x in resolve_data.get("remaining_ids") or []:
        try:
            rem_set.add(int(x))
        except (TypeError, ValueError):
            pass

    hit_queries = {str(h.get("query") or "").strip() for h in hits}
    llm_hits = 0
    for h in hits:
        try:
            pid = int(h["problem_id"])
        except (TypeError, ValueError, KeyError):
            continue
        if pid in id_set:
            continue
        matched.append(h)
        id_set.add(pid)
        rem_set.add(pid)
        llm_hits += 1

    still = [
        u
        for u in unmatched
        if isinstance(u, dict) and str(u.get("query") or "").strip() not in hit_queries
    ]
    hit_ids = {str(h.get("problem_id")) for h in hits}
    still = [
        u
        for u in still
        if re.sub(r"(?i)^lc\s*", "", str(u.get("query") or "").strip()) not in hit_ids
    ]

    problem_ids = list(id_set)
    out = dict(resolve_data)
    out["matched"] = matched
    out["unmatched"] = still
    out["problem_ids"] = problem_ids
    out["remaining_ids"] = list(rem_set)
    out["passed_ids"] = [p for p in problem_ids if p not in rem_set]
    out["matched_count"] = len(problem_ids)
    out["remaining_count"] = len(rem_set)
    out["passed_count"] = len(problem_ids) - len(rem_set)
    out["llm_enriched"] = llm_hits
    return out


def build_plan_draft(
    *,
    user_text: str,
    resolve_data: dict[str, Any],
) -> dict[str, Any]:
    days = extract_days(user_text)
    daily = extract_daily_goal(user_text)
    remaining = [
        int(x)
        for x in (resolve_data.get("remaining_ids") or [])
        if str(x).isdigit() or isinstance(x, int)
    ]
    unmatched_q = [
        str(u.get("query") or "")
        for u in (resolve_data.get("unmatched") or [])
        if isinstance(u, dict)
    ]
    title = "自定义力扣题单"
    lower = user_text.lower()
    if "字节" in user_text or "bytedance" in lower:
        title = "字节高频题单"
    elif "hot100" in lower or "hot 100" in lower:
        title = "Hot100 题单"

    return {
        "goal_type": "custom",
        "goal_ref": title,
        "title": title,
        "problem_ids": remaining,
        "all_matched_ids": list(resolve_data.get("problem_ids") or []),
        "passed_count": int(resolve_data.get("passed_count") or 0),
        "remaining_count": len(remaining),
        "unmatched": unmatched_q,
        "ambiguous_count": len(resolve_data.get("ambiguous") or []),
        "days": days,
        "daily_goal": daily,
        "skip_passed": True,
        "catalog_enriched": int(resolve_data.get("catalog_enriched") or 0),
        "llm_enriched": int(resolve_data.get("llm_enriched") or 0),
        "resolve_note": str(resolve_data.get("note") or ""),
    }


def run_plan_resolve(
    *,
    tools: Any,
    state: dict[str, Any],
) -> dict[str, Any]:
    """执行解析；返回 state patch。"""
    session_id = str(state.get("session_id") or "")
    user_public_id = str(state.get("user_public_id") or "")
    problem_id = int(state.get("problem_id") or 0) or None
    text = latest_user_text(list(state.get("messages") or []))
    ids = extract_lc_ids(text)
    queries = [str(i) for i in ids]

    resolve_data: dict[str, Any] = {}
    try:
        raw = tools.exec_tool_sync(
            tool_name="resolve_problem_refs",
            params={"queries": queries, "raw_text": text[:8000] if text else ""},
            session_id=session_id,
            problem_id=problem_id,
            user_public_id=user_public_id,
        )
        resolve_data = _parse_tool_json(raw if isinstance(raw, str) else json.dumps(raw))
    except Exception as exc:  # noqa: BLE001
        logger.warning("plan_resolve: resolve_problem_refs failed: %s", exc)
        resolve_data = {"ok": False, "matched": [], "unmatched": [], "note": str(exc)}

    if not resolve_data.get("matched") and ids:
        matched = []
        unmatched = []
        for pid in ids:
            hit = lookup_by_id(pid)
            if hit:
                matched.append(
                    {
                        **hit,
                        "query": str(pid),
                        "from_catalog": True,
                        "accepted": False,
                        "mastered": False,
                        "done": False,
                    }
                )
            else:
                unmatched.append({"query": str(pid)})
        resolve_data = {
            "ok": True,
            "matched": matched,
            "unmatched": unmatched,
            "ambiguous": [],
            "problem_ids": [m["problem_id"] for m in matched],
            "remaining_ids": [m["problem_id"] for m in matched],
            "passed_ids": [],
            "matched_count": len(matched),
            "remaining_count": len(matched),
            "passed_count": 0,
            "note": "Java resolve 不可用，已用本地 lc_catalog 兜底",
        }
    else:
        resolve_data = enrich_with_catalog(resolve_data)

    leftover = list(resolve_data.get("unmatched") or [])
    for row in resolve_data.get("ambiguous") or []:
        if isinstance(row, dict) and row.get("query"):
            leftover.append({"query": str(row.get("query"))})
    if leftover and user_public_id:
        hits = llm_match_unmatched(
            unmatched=leftover,
            user_public_id=user_public_id,
            context_snippet=text[:800],
        )
        resolve_data = merge_llm_hits(resolve_data, hits)

    draft = build_plan_draft(user_text=text, resolve_data=resolve_data)
    patch: dict[str, Any] = {
        "plan_draft": draft,
        "phase": "plan_active",
    }
    if draft.get("remaining_ids") or draft.get("problem_ids"):
        patch["pending_followup"] = {
            "action": "confirm_plan",
            "preview": {
                "problem_ids": draft.get("problem_ids"),
                "days": draft.get("days"),
                "daily_goal": draft.get("daily_goal"),
                "goal_type": "custom",
                "goal_ref": draft.get("goal_ref"),
                "title": draft.get("title"),
                "skip_passed": True,
                "unmatched": draft.get("unmatched") or [],
            },
        }
    return patch
