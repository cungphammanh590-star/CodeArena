"""Nex 首轮/空闲意图：规则优先；低置信留给 confirm。"""

from __future__ import annotations

import re

from app.coach.confirm import (
    CHOICE_BACK,
    CHOICE_CONTINUE,
    CHOICE_IN_PROBLEM,
    CHOICE_META,
    CHOICE_NEW,
    CHOICE_PLAN_DP,
    CHOICE_PLAN_GOOGLE,
    CHOICE_PLAN_HOT100,
    CHOICE_START_FIRST,
    CHOICE_STATUS,
    CHOICE_TODAY_TASKS,
    CHOICE_ADJUST_PLAN,
)
from app.coach.phases import SmartIntent

# 低于此置信 → 走 confirm（向用户要选项），不猜 route
CONFIRM_CONFIDENCE_THRESHOLD = 0.75

_OFF_TOPIC = (
    "天气",
    "写诗",
    "讲个笑话",
    "股票",
    "恋爱",
    "今天吃什么",
    "帮我写小说",
    "chatgpt",
)
_STATUS = (
    "今天怎么样",
    "今日总结",
    "刷得怎么样",
    "薄弱",
    "掌握",
    "进度",
    "due",
    "复习队列",
    "今天进度",
    "今日复习",
    "到期复习",
    "间隔复习",
)
_CONTINUE = (
    "继续",
    "接着做",
    "还没过",
    "没过的",
    "上次那题",
    "继续刷",
    "接着刷",
)
_NEW = (
    "下一题",
    "换一题",
    "推荐",
    "开刷",
    "刷什么",
    "做哪题",
    "新题",
)
_META = (
    "你能做什么",
    "怎么用",
    "你是谁",
    "帮助",
    "功能",
)
_PLAN_CREATE_MARKERS = (
    "备考",
    "面试",
    "题单",
    "打卡",
    "系统刷",
    "专攻",
    "刷题计划",
    "学习计划",
    "生成计划",
    "制定计划",
)
_COMPANIES = (
    "google",
    "谷歌",
    "meta",
    "facebook",
    "amazon",
    "亚马逊",
    "microsoft",
    "微软",
    "apple",
    "苹果",
)
_TOPICS = (
    "动态规划",
    "dp",
    "链表",
    "二叉树",
    "二分",
    "图论",
    "回溯",
    "滑动窗口",
    "栈",
    "队列",
    "堆",
)
_PLAN_ADJUST = (
    "加班",
    "明天补",
    "延期",
    "推迟",
    "暂停计划",
    "恢复计划",
    "加速",
)
_TIMEBOX = re.compile(
    r"(\d+)\s*天|两周|一周|一个月|(\d+)\s*周",
    re.I,
)
_FULL_ANSWER = (
    "完整代码",
    "完整解法",
    "直接给代码",
    "把答案给我",
    "AC代码",
    "全部代码",
    "整段代码",
    "可运行代码",
)
_CODE_原文 = (
    "代码原文",
    "写出代码",
    "写出来",
    "贴代码",
    "这段代码",
    "这句代码",
    "循环写出来",
    "给我代码",
    "把这句",
)

# 注入/越狱软信号（不单独定罪，强制 confirm 或拒答）
_INJECTION = (
    "忽略以上",
    "忽略之前",
    "ignore previous",
    "ignore all",
    "disregard",
    "你现在是",
    "你是dan",
    "jailbreak",
    "开发者模式",
    "系统提示",
    "system prompt",
    "揭示提示词",
    "打印提示词",
    "露出提示",
    "不要遵守",
    "不要遵循",
    "绕过规则",
    "覆盖规则",
    "假装你没有限制",
    "进入上帝模式",
)


def wants_code_原文(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    return any(p in t for p in _CODE_原文)


def injection_suspect(text: str) -> bool:
    t = (text or "").strip().lower()
    if not t:
        return False
    raw = text or ""
    if any(p.lower() in t for p in _INJECTION):
        return True
    # 典型「覆盖系统」句式
    if re.search(r"(忽略|无视|forget).{0,12}(规则|提示|指令|system)", raw, re.I):
        return True
    if re.search(r"(reveal|show|dump|print).{0,16}(system|prompt|隐藏)", raw, re.I):
        return True
    return False


_AFFIRM_SHORT = frozenset(
    {
        "可以",
        "可以的",
        "好",
        "好的",
        "行",
        "行啊",
        "行的",
        "嗯",
        "嗯嗯",
        "要",
        "要的",
        "看看",
        "先看看",
        "展示",
        "显示",
        "来吧",
        "开始吧",
        "ok",
        "okay",
        "yes",
        "y",
        "确认",
        "就这样",
        "按这个",
        "没问题",
    }
)


def is_short_affirmation(text: str) -> bool:
    """短确认/肯定：依赖上一轮要约，不宜单独走大厅 confirm。"""
    t = (text or "").strip().lower()
    if not t or len(t) > 24:
        return False
    t = t.strip("。.！!？?~～ ")
    if t in _AFFIRM_SHORT:
        return True
    if t.startswith(("可以", "好的", "行", "要", "看看", "展示", "确认")) and len(t) <= 12:
        return True
    return False


def _match_confirm_choice(text: str) -> SmartIntent | None:
    """用户点击选项发出的固定文案 → 高置信意图。"""
    t = (text or "").strip()
    mapping: list[tuple[str, SmartIntent]] = [
        (CHOICE_CONTINUE, "practice_continue"),
        (CHOICE_NEW, "practice_new"),
        (CHOICE_STATUS, "status_review"),
        (CHOICE_IN_PROBLEM, "in_problem_help"),
        (CHOICE_META, "meta_product"),
        (CHOICE_BACK, "meta_product"),
        (CHOICE_PLAN_GOOGLE, "plan_create"),
        (CHOICE_PLAN_DP, "plan_create"),
        (CHOICE_PLAN_HOT100, "plan_create"),
        (CHOICE_TODAY_TASKS, "plan_status"),
        (CHOICE_START_FIRST, "plan_status"),
        (CHOICE_ADJUST_PLAN, "plan_adjust"),
    ]
    for phrase, intent in mapping:
        if t == phrase or t.endswith(phrase):
            return intent
    return None


def _looks_like_plan_create(text: str) -> tuple[bool, float]:
    """返回 (是否像建计划, 置信度)。集合信号 + 目标槽。"""
    t = (text or "").strip()
    if not t:
        return False, 0.0
    lower = t.lower()
    has_marker = any(p in t for p in _PLAN_CREATE_MARKERS) or bool(_TIMEBOX.search(t))
    has_company = any(c in lower for c in _COMPANIES)
    has_topic = any(p.lower() in lower if p.isascii() else p in t for p in _TOPICS)
    has_list = "hot100" in lower or "hot 100" in lower or "热题" in t
    has_goal = has_company or has_topic or has_list
    if has_marker and has_goal:
        return True, 0.9
    if has_marker and _TIMEBOX.search(t):
        return True, 0.7  # 有时间盒但目标不清 → 可能 confirm
    if ("面试" in t or "备考" in t) and has_company:
        return True, 0.88
    if ("系统刷" in t or "专攻" in t or "题单" in t) and has_topic:
        return True, 0.88
    return False, 0.0


def classify_smart_intent(
    text: str,
    *,
    bound_problem_id: int = 0,
    action: str = "",
    topic: str = "",
) -> tuple[SmartIntent, float]:
    t = (text or "").strip()
    act = str(action or "").strip()
    topic = (topic or "").strip()
    if act in {"close", "diagnose", "deep_analysis"}:
        return "in_problem_help", 0.95
    if not t:
        return "clarify", 0.4

    # 确认选项原样回传：直接高置信，避免再次 confirm 循环
    choice_intent = _match_confirm_choice(t)
    if choice_intent is not None:
        return choice_intent, 0.98

    # 专题续聊信号
    if topic and any(p in t for p in ("继续这个专题", "继续聊", "还是这个专题", topic)):
        if any(p in t for p in _STATUS):
            return "status_review", 0.9
        return "in_problem_help" if bound_problem_id > 0 else "clarify", 0.75

    if any(p in t for p in _FULL_ANSWER):
        return "want_full_answer", 0.9
    if any(p in t for p in _OFF_TOPIC) and not any(
        x in t for x in ("题", "刷", "leetcode", "力扣", "算法", "链表", "树", "动态规划")
    ):
        return "off_topic", 0.85

    # 计划族：须压在 practice_new / status 之前
    if any(p in t for p in _PLAN_ADJUST) and ("计划" in t or "今天" in t or "明天" in t):
        return "plan_adjust", 0.85
    plan_ok, plan_conf = _looks_like_plan_create(t)
    if plan_ok:
        return "plan_create", plan_conf
    if any(p in t for p in ("今天刷什么", "今日任务", "计划进度", "还剩几天")):
        return "plan_status", 0.85

    if any(p in t for p in _META) and len(t) < 40:
        return "meta_product", 0.8
    if any(p in t for p in _STATUS):
        return "status_review", 0.85
    if any(p in t for p in _CONTINUE):
        return "practice_continue", 0.8
    if any(p in t for p in _NEW):
        return "practice_new", 0.8

    if re.search(r"\b\d{1,4}\b", t) or "题" in t:
        return "in_problem_help", 0.75 if bound_problem_id <= 0 else 0.9

    if bound_problem_id > 0:
        # 绑题后短句默认题内，但仍可能灰区（由注入标记另处理）
        if len(t) <= 80:
            return "in_problem_help", 0.72
        return "in_problem_help", 0.7

    if len(t) <= 6 and t in {"你好", "在吗", "嗨", "hello", "hi"}:
        return "meta_product", 0.6

    return "clarify", 0.45


def intent_to_phase(
    intent: SmartIntent,
    *,
    bound: bool,
    session_kind: str = "lobby",
) -> str:
    kind = session_kind or "lobby"
    if kind == "topic" and intent == "status_review":
        return "today_brief"
    if intent in {"plan_create", "plan_adjust"}:
        return "plan_active"
    if intent == "plan_status":
        return "today_brief"
    if bound and intent in {
        "in_problem_help",
        "want_full_answer",
        "clarify",
    }:
        return "in_problem"
    mapping = {
        "practice_continue": "lobby",
        "practice_new": "prep",
        "status_review": "today_brief",
        "in_problem_help": "in_problem" if bound else "lobby",
        "meta_product": "lobby",
        "off_topic": "lobby",
        "want_full_answer": "in_problem" if bound else "prep",
        "clarify": "lobby" if kind != "topic" else "today_brief",
        "plan_create": "plan_active",
        "plan_status": "today_brief",
        "plan_adjust": "plan_active",
    }
    return mapping.get(intent, "lobby")


def should_reclassify(*, phase: str, turn_count: int, user_text: str) -> bool:
    if turn_count <= 1:
        return True
    if phase in {"lobby", "today_brief", "prep", "wrap", "plan_active"}:
        return True
    t = user_text or ""
    if _match_confirm_choice(t) is not None:
        return True
    if injection_suspect(t):
        return True
    if any(p in t for p in _OFF_TOPIC + _STATUS + _NEW + _CONTINUE + _META + _PLAN_CREATE_MARKERS + _PLAN_ADJUST):
        return True
    if _looks_like_plan_create(t)[0]:
        return True
    return False