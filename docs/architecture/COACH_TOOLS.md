# Nex 陪练工具与编排器-执行器模式

## 1. 当前工具清单（对齐原 LangGraph smart_agent）

| 工具名 | 类型 | 执行方 | 说明 |
|--------|------|--------|------|
| `get_session_binding` | READ | **Java** | 会话是否绑题 |
| `bind_problem` | WRITE | **Java** | 绑题 / 返回候选 |
| `get_current_code` | READ | **Java** | 当前题最新提交代码 |
| `get_error_summary` | READ | **Java** | 错因/挣扎统计 |
| `get_latest_submission` | READ | **Java** | 用户最近提交（无源码） |
| `list_unpassed_problems` | READ | **Java** | 未 AC 列表 |
| `get_user_profile_summary` | READ | **Java** | 画像摘要 |
| `get_topic_mastery` | READ | **Java** | 按标签聚合 |
| `get_problem_mastery` | READ | **Java** | 单题掌握 + SRS 卡片摘要 |
| `suggest_next_problems` | READ | **Java** | 续刷/新荐候选 |
| `resolve_problem_refs` | READ | **Java** | 解析力扣题号/标题 + 已刷/未刷（计划线由 `plan_resolve` 节点必调） |
| `preview_study_plan` | READ | **Java** | 计划预览（不落库）+ 容量协商 |
| `generate_study_plan` | WRITE | **Java** | 按目标/自定义题单生成题单 ± 多日日程 |
| `get_today_tasks` | READ | **Java** | 今日计划 + SRS 复习（`plan` / `review`） |
| `get_review_due` | READ | **Java** | 仅 SRS 到期复习 |
| `get_active_plan` | READ | **Java** | 进行中计划摘要 |
| `recall_memories` | READ | **Java** | 跨会话长期记忆 |
| `remember` | WRITE | **Java** | 写入长期记忆 |
| `forget_memory` | WRITE | **Java** | 软删长期记忆 |
| `get_last_advice` | READ | **Python 本地** | 仅读本会话消息，无 DB |

内部（不进 LLM TOOL_SPECS）：`append_coach_turn`、`sync_session_state`、`get_session_context` — hydrate / stream 落轮次用。

> CodeArena 中：**禁止** LLM/Python 直连数据库；Java 持有 Repository。

## 2. 编排器-执行器（Orchestrator-Executor）

```mermaid
sequenceDiagram
  participant C as Client
  participant G as Gateway
  participant J as business-service
  participant P as llm-service
  participant L as Upstream LLM

  C->>G: POST /api/coach/stream
  G->>P: SSE 对话（Header: X-User-Public-Id）
  P->>J: GET /internal/users/llm（取该用户 Key）
  P->>L: 用该用户 Key 推理 + tool_calls
  L-->>P: tool_name + params
  P->>J: POST /internal/tools/exec<br/>X-Internal-Token + X-User-Public-Id
  Note over J: CoachTool 策略分发<br/>查库/写会话
  J-->>P: JSON 结果
  P->>L: ToolMessage
  L-->>P: 自然语言
  P-->>C: SSE tokens
```

口诀：**AI 定策略，Python 传令，Java 执行，库只听 Java。谁填的 Key，就用谁的 Key。**

## 3. Java 策略接口化

```
com.codearena.business.coach
  tool/
    CoachTool / Context / Result / Registry
    impl/*Tool.java          # 每工具一文件，@Component 自动注册
  web/
    external/CoachController           # /api/coach/**
    internal/InternalToolController    # /internal/tools/**
```

新增工具：在 `tool.impl` 实现 `CoachTool` + `@Component`，**不必改 Controller**。

### 内网协议

```http
POST /internal/tools/exec
X-Internal-Token: <shared>
X-User-Public-Id: usr_xxx
Content-Type: application/json

{
  "tool_name": "suggest_next_problems",
  "params": { "limit": 3 },
  "session_id": "biz-...",
  "problem_id": 215
}
```

- **不要**把 `/internal/**` 配进公网 Gateway / Nginx（当前仅代理 `/api`、`/submit`、`/health`）。
- Token：`codearena.internal.token` / 环境变量 `CODEARENA_INTERNAL_TOKEN`。

写类长任务的 `202 + task_id` 轮询可后续加；当前 WRITE 仅 `bind_problem` 同步短路径。

## 4. Python 侧

- 图：`app/coach/graph.py`（hydrate → … → persist）
- 工具客户端：`app/services/tool_client.py`
- Checkpoint：`app/coach/checkpoint.py`

## 5. 按用户 LLM Key

| 路径 | 说明 |
|------|------|
| `user_llm_settings` | 按用户存 provider / model / api_key |
| `/api/users/me/llm/**`、`/api/ops/llm/**` | 配置入口 |
| `GET /internal/users/llm` | llm-service 取明文 Key（仅内网） |

公开 API 只返回掩码；可选 `CODEARENA_LLM_KEY_SECRET` AES 落库。

图与记忆：[COACH_LANGGRAPH.md](./COACH_LANGGRAPH.md)。边界：[BUSINESS_FLOW.md](./BUSINESS_FLOW.md)。
