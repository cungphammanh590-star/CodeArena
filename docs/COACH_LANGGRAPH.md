# Coach / LangGraph 链路说明（Profile-Centric + dual graphs）

本文描述陪练子系统的端到端数据流与可调位点。  
**采集与陪练解耦**：`/submit` 永不进入本文链路。  
**双图**：`llm.provider=ollama` → LocalGraph；`api` → ApiGraph。  
**画像中心**：Graph 注入只读 `user_profile` + `current_code`；意图驱动优化 / 推荐 / 日回顾。

---

## 1. 三接口总览

```text
力扣提交
  │
  ▼
① POST /submit              ← 只写 problems / submissions / stats
  │
  ▼
② POST /api/coach/prepare   ← 读库 + 模板 opening
  │  submission | problem_id | mode=daily_review|recommend
  ▼
③ POST /api/coach/stream    ← { session_id, message?, action? }
      └─ LocalGraph / ApiGraph
```

`action` 可选：`close` | `show_skeleton` | `diagnose` | `deep_analysis` | **`daily_review`** | **`recommend`** | `optimize`。

**快车道**：`daily_review` / `recommend` **跳过意图分类**，直达子图。  
自然语言（如「换一题」「今天刷得怎么样」）仍走意图识别。

SSE：`ready` / `token` / `offer_exit` / `answer_egress` / `diagnose` / `deep_analysis` / `fallback` / `done` / `error`。

入口 UI：扩展弹窗「今日总结 / 推荐下一题」→ `/coach?mode=…&action=…`；`coach.html` 内同名按钮。

---

## 2. 模块与文件

| 路径 | 职责 |
|------|------|
| `coach/state.py` | `CoachState`（画像、代码、intent、推荐候选等） |
| `coach/profile.py` | 只读聚合用户画像 |
| `coach/intent.py` | 规则 + Local 判别 / Api 结构化意图 |
| `coach/recommend.py` | 共享推荐核心（零 LLM 选题） |
| `coach/daily_review.py` | 日回顾事实组装 |
| `coach/structure_diff.py` | Local 结构特征对比（AC 源码脱敏） |
| `coach/graphs/local.py` | LocalGraph |
| `coach/graphs/api.py` | ApiGraph |
| `coach/graphs/skill_nodes.py` | 推荐/回顾/优化共享节点 |
| `coach/answer_egress.py` | 显式看思路出口 |
| `coach/service.py` | prepare + `chat_stream` 注入画像 |

---

## 3. 双图能力矩阵

```text
prepare_intent → route
  ├ action 快车道 → recommend / daily_review / …
  └ intent → optimize | recommend | daily_review | chat | show_answer
```

| 能力 | ApiGraph | LocalGraph (7B) |
|------|----------|-----------------|
| 意图 | 规则 + 结构化标签 | **规则优先** + 判别式标签 |
| 优化 | 瓶颈 + 方向（禁完整 AC 码） | **结构特征 Diff + 陷阱模板**；禁 AC 源码进 Prompt |
| 推荐 | 共享 DB 规则 + 润色 | **相同** |
| 日回顾 | 可解读 | **只念聚合数据** |
| 出口 | 诊断 / 精析 | 硬回合 + 关键词；看思路仅显式 |
| 送模上下文 | 可压缩 | **画像 + 近 2 轮 + 用户代码片段** |

**推荐触发权**：仅用户显式（按钮或自然语言）。本版本**无** `progress_gate`；后续版本拟基于正确率等做被动推荐。

---

## 4. 推荐（Hot100）与看思路

**推荐候选池**：`coach/data/hot100.json`（静态 Hot100），**不是**本地 `problems` 表。  
**推荐下一题**（`action=recommend`）：只推未 AC **新题**；级联续刷 → 同标签 → 薄弱。  
**今日复习**（`action=review`）：只出已 AC 且到期的 **旧题**（固定间隔 MVP，默认 7 天）。  
**今日总结**（`action=daily_review`）：只念聚合事实，可提示 due 数量，不选题。  
近 7 日新题推荐写入 `coach_recommendation_log` 去重。  
Hot100 ≥90% 且无未 AC 时，推荐返回完成引导（请去复习或图谱）。

**看思路（显式出口）**：

1. 本题历史 Accepted 代码（标明来源）— **仅** `show_skeleton` / answer_egress  
2. `answer_skeletons.json` curated 文字  
3. KG annotation 骨架  
4. 按难度的通用提纲  

Local **优化路径**可用历史 AC **提特征**，但不得把 AC `source_code` 注入模型 Prompt。

---

## 5. 微调检查清单

1. 人设 / 防泄题 → `prompts.py`  
2. 模板首句 → `opening.py`  
3. 失稳阈值 → `exit_detect.py` / `state.py`  
4. 骨架文案 → `answer_skeletons.json`  
5. 意图规则 → `intent.py`  
6. Hot100 清单 → `coach/data/hot100.json`；推荐级联 → `recommend.py`  
7. 复习间隔 → `review.py` 常量  
8. 图边与节点 → `graphs/local.py`、`graphs/api.py`

相关：`docs/DATA_MODEL.md`、`docs/SUBMISSION_CAPTURE_INCIDENT.md`。
