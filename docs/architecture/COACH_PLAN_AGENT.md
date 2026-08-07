# 刷题计划 Agent 扩充计划与说明

本文在**保持单一 Orchestrator（现有 LangGraph）**的前提下，说明如何按**用户目标**生成可刷的题单与（可选）多日日程。  
「准备 Google 面试」只是目标类型之一；同类还有「刷动态规划」「专攻链表」「按 Hot100 打卡 21 天」等。

相关基线：[COACH_LANGGRAPH.md](./COACH_LANGGRAPH.md) · [COACH_TOOLS.md](./COACH_TOOLS.md) · [MODULAR_BUSINESS.md](./MODULAR_BUSINESS.md)

---

## 0. 产品抽象（相对「仅公司面试」的修正）

### 真正要交付的

用户用自然语言描述**想刷什么 +（可选）多久刷完** → 系统产出：

1. **题单**（`problem_lists`）：一组有序题目，可在学习页浏览  
2. **（可选）日程计划**（`study_plans` + `plan_daily_tasks`）：把题单按天切开，支撑「今天刷什么 / 改期 / 提醒」

公司备考、专题攻坚、薄弱点补强，都是同一条流水线，只是**题池怎么选**不同。

### 目标类型 `goal_type`

| goal_type | 典型话术 | 题池来源 |
|-----------|----------|----------|
| `company` | 准备 Google / Meta 面试 | `goal_problem_banks`（company 维）或标签近似 |
| `topic` | 专刷动态规划 / 链表 / 二分 | `problem_stats.topic_tags` / `problems.tags` 匹配 |
| `list` | 按 Hot100 / 某用户题单打卡 | 已有 `problem_lists` + items |
| `weak` | 补我薄弱的图论 | 掌握/挣扎统计 × 标签（P1） |
| `custom` | 混合：DP + 中等难度，大约 40 题 | LLM 填结构化过滤条件，Java 执行过滤 |

工具**不要**做成只认 `company` 的 `generate_interview_plan`；统一为：

**`generate_study_plan`**（可保留旧名作别名，实现同一 Service）。

---

## 1. 目标与非目标

### 目标

| 用户话术 | 系统行为 |
|----------|----------|
| 「准备 Google，30 天」 | `goal_type=company` → 题单 + 30 日日程 |
| 「我想系统刷一遍动态规划，两周」 | `goal_type=topic` → DP 题池 + 14 日日程 |
| 「给我建一个链表专题题单」（不提天数） | 只建题单，或默认短周期；`schedule=false` |
| 「按 Hot100 打卡」 | `goal_type=list`, `goal_ref=hot100` |
| 「今天刷什么」 | 有日程 → 今日 tasks；仅有题单 → 题单进度/下一题 |
| 「今天加班，明天补」 | 仅对**有日程**的 plan 做 `adjust_plan` |
| （系统侧）每日提醒 | Java 调度（有日程才推） |

### 非目标（本期不做）

- 多 Agent；LLM 自行点名几十个题号排期  
- 外网实时爬面经（公司题靠种子 / 标签）  
- 为每人注册 N 个独立 Job  

---

## 2. 工具扩充

### 2.1 核心写工具：`generate_study_plan`

| 项 | 定义 |
|----|------|
| Kind | WRITE |
| 职责 | **一次**完成：解析目标 → 选池 →（可选）分阶段 → 写题单 →（可选）写日程 → 返回摘要 |

**参数（给 LLM 的契约）：**

```json
{
  "goal_type": "company | topic | list | weak | custom",
  "goal_ref": "Google | 动态规划 | hot100 | ...",
  "title": "可选展示名",
  "days": 30,
  "daily_goal": 3,
  "schedule": true,
  "difficulty": "optional Easy|Medium|Hard|mixed",
  "limit": 100
}
```

| 参数 | 规则 |
|------|------|
| `goal_type` + `goal_ref` | 必填语义；confirm 阶段可先问「公司还是专题」 |
| `days` | `schedule=true` 时必填（或默认 14）；`schedule=false` 可省略 |
| `daily_goal` | 可选，默认 clamp 到 2～5 |
| `schedule` | 默认 `true`（用户说了「天/周」）；只说「建个题单」→ `false` |
| `limit` | 题池上限，防一次拉爆 |

**Java 内策略（题池解析器）：**

```text
PlanGenerationService.generate(cmd)
  pool = GoalPoolResolver.resolve(goal_type, goal_ref, filters)
       ├── CompanyPoolResolver
       ├── TopicPoolResolver      // 标签包含匹配 + 难度过滤
       ├── ListPoolResolver       // 已有题单
       ├── WeakPoolResolver       // P1
       └── CustomPoolResolver     // tags[] + difficulty
  stages = optionalStageSplit(pool, goal_type)  // company/topic 可分基础→强化；list 可按原序
  list   = createProblemList(user, title, pool)
  if schedule:
       write study_plans + plan_daily_tasks(list, days, daily_goal)
  return summary(list_id, plan_id?, counts...)
```

分阶段仍是**规则**，不是第二个 Agent。

### 2.2 配套工具

| 优先级 | 工具 | 说明 |
|--------|------|------|
| P0 | `generate_study_plan` | 上表 |
| P0 | `get_today_tasks` | 有日程读今日；无日程但有 active list → 返回「题单下一批」也可 |
| P0 | `get_active_plan` | digest：`goal_type/ref`、list_id、plan_id、剩余天 |
| P1 | `adjust_plan` / `set_plan_status` | 仅日程计划 |
| P2 | `preview_goal_pool` | 生成前预览题量/标签分布（可选） |
| P2 | `complete_today_task` | 与提交/掌握联动 |

废弃专名：`generate_interview_plan`、`get_company_question_tags` 不再作为主契约（若兼容可 alias → 同一实现）。

### 2.3 铁律

1. LLM **禁止**提交 `problem_ids[]` 长列表；只交目标与约束。  
2. 题单与日程同一事务；失败全滚。  
3. 同用户多个 active：P0 限制「日程类仅一个 active」；题单可多个，digest 标「当前绑定」。  
4. 仍走 `/internal/tools/exec`；逻辑在 `learning.plan.service`。

---

## 3. LangGraph 调整（摘要）

拓扑不变：`hydrate → classify → refuse|offer|confirm|agent⇄tools → finalize → persist`。

| 节点 | 相对公司-only 版的变化 |
|------|------------------------|
| **confirm** | 缺的是 `goal_type/goal_ref`，不是死问公司。选项例：专题(DP/链表/…) / 公司(Google/…) / 已有题单(Hot100) / 只要题单不要日程 |
| **hydrate** | `plan_digest` 含 goal 文案：「动态规划·14天」或「Google·30天」 |
| **agent** | system：有明确目标就调 `generate_study_plan`；只有「换一题」仍走单题工具 |
| **phase** | 仍用 `plan_active`，语义改为「有进行中的刷题计划/打卡」，不限定面试 |

---

## 4. 意图识别调整

### 4.1 Intent（保持计划族，话术更广）

| Intent | 话术覆盖 |
|--------|----------|
| `plan_create` | 准备××面试；系统刷××；建××题单；打卡 N 天；专攻某标签 |
| `plan_adjust` / `plan_status` / pause·resume | 同前（针对已有日程） |

### 4.2 与 `practice_new` / 专题会话的边界

| 话术 | Intent | 说明 |
|------|--------|------|
| 「下一题 / 换一题」 | `practice_new` → offer | 单题 CTA，**不**建计划 |
| 「刷链表」（已有 topic session） | 短求助 → 题内/专题陪练 | 若带「系统刷/两周/题单」→ `plan_create` |
| 「系统刷一遍链表，两周」 | `plan_create` + `topic` | 建题单+日程 |
| 「准备 Google，30 天」 | `plan_create` + `company` | 同上 |

规则启发（用于提高 `plan_create` 置信）：

- 时间盒：`N天` / `两周` / `一个月` / `打卡`  
- 集合词：`题单` / `计划` / `系统刷` / `专攻` / `备考` / `面试`  
- 目标槽：公司名 **或** 已知标签词表（与 `topic` 会话词可共享）

缺槽时 confirm：先问「按公司、按专题、还是按现有题单？」，再问是否排日程。

### 4.3 路由伪代码

```text
if intent == plan_create:
  if missing(goal_type) or missing(goal_ref):
    return confirm
  if schedule_implied and missing(days):
    return confirm  # 或默认 14
  return agent

# practice_new → offer 保持，但不得抢在 plan_create 之前匹配
```

---

## 5. 数据模型（广义）

```sql
-- 刷题计划（日程容器；题单通过 list_id 关联）
CREATE TABLE study_plans (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    goal_type       VARCHAR(32) NOT NULL,  -- company|topic|list|weak|custom
    goal_ref        VARCHAR(128) NOT NULL,
    title           VARCHAR(256),
    list_id         VARCHAR(64) REFERENCES problem_lists(id),
    total_days      INT,                   -- schedule=false 时可 NULL
    start_date      DATE,
    end_date        DATE,
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE plan_daily_tasks ( ... );     -- 同前；仅 schedule 时写入
CREATE TABLE plan_notifications ( ... );

-- 可选：目标题库种子（company / 运营专题包）
CREATE TABLE goal_problem_banks (
    goal_type       VARCHAR(32) NOT NULL,
    goal_ref        VARCHAR(128) NOT NULL,
    problem_id      INT NOT NULL,
    stage_hint      VARCHAR(32),
    sort_order      INT DEFAULT 0,
    PRIMARY KEY (goal_type, goal_ref, problem_id)
);
```

`topic` 型可不依赖 `goal_problem_banks`，直接扫标签；`company` / 运营包优先走种子表。

表名用 `study_plans`，避免「只有面试」的语义绑架；与 SRS 复习队列仍同属 `learning.plan` 子域。

---

## 6. `generate_study_plan` 执行流程（落地版）

```text
1. Normalize goal_type/ref（谷歌→Google；「DP」→「动态规划」）
2. Resolve pool（Resolver 策略）
3. Apply filters（difficulty、limit、排除已 mastered 可选）
4. Create problem_list + items（source=plan_gen）
5. If schedule:
     bucket by days × daily_goal（前序阶段题优先）
     insert study_plans + plan_daily_tasks
6. Return:
     { list_id, plan_id?, goal_type, goal_ref, total_questions,
       total_days?, daily_avg?, today_count?, note }
```

**只建题单**：用户「给我一个二分专题题单」→ `schedule=false` → 有 `list_id`、无 daily tasks；digest 仍可 `plan_active` 表示「当前学习目标是该题单」，或仅绑 `active_list_id`（与 `learning_prefs` 对齐）。

---

## 7. 实施切片（修订）

### P0

1. 表 `study_plans` / `plan_daily_tasks` + `goal_problem_banks`（Google 种子 + 可选 DP 种子）  
2. `TopicPoolResolver` + `CompanyPoolResolver` + `ListPoolResolver`  
3. `generate_study_plan` + `get_today_tasks` + `get_active_plan`  
4. Intent：`plan_create` 覆盖公司 **与** 专题；confirm 问 goal 类型  
5. 验收话术两条：  
   - 「准备 Google，30 天」→ 题单+日程  
   - 「系统刷动态规划，14 天」→ 题单+日程  

### P1

调整/暂停、提醒、`weak` 解析器、agent 工具分组。

### P2

`custom` 过滤、预览池、邮件、运营导入 bank。

---

## 8. 风险

| 风险 | 对策 |
|------|------|
| 「刷链表」误建计划 | 需时间盒/题单/系统刷等集合信号；否则走专题陪练 |
| 标签名不统一 | 同义词表（DP↔动态规划）；confirm 展示规范名 |
| 题池为空 | failure + 建议换 goal 或放宽 difficulty |
| 与 `session_kind=topic` 混淆 | topic **会话**是陪练线；topic **计划**是题单/日程；可并存，digest 写清 |

---

## 9. 一句话

> 工具要生成的是**「按目标装配的题单 ± 日程」**，不是「Google 专用面试生成器」。  
> 公司 / 专题 / 现有题单 / 薄弱点，都是 `goal_type` 下的题池策略；图与意图仍是单一 Orchestrator + `plan_create`。
