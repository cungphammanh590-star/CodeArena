# 陪练记忆模块

三层记忆，职责分离。口诀：**短期靠图状态，中期靠会话表，长期靠用户记忆表；库只听 Java。**

详见：[COACH_TOOLS.md](./COACH_TOOLS.md)、[BUSINESS_FLOW.md](./BUSINESS_FLOW.md)、**图与 State/Checkpoint 定义** [COACH_LANGGRAPH.md](./COACH_LANGGRAPH.md)。

## 分层

| 层 | 名称 | 存储 | 归属 | 生命周期 |
|----|------|------|------|----------|
| L1 | 运行时 checkpoint | Redis（优先）/ MemorySaver | llm-service | TTL 默认 7 天；非审计源 |
| L2 | 会话记忆 | `coach_sessions` + `coach_turns` | Java / Postgres | 可恢复、可审计；支持 `topic` |
| L3 | 长期教练记忆 | `user_coach_memories` | Java / Postgres | 跨会话；软删 |

## LangGraph 拓扑（优化后）

```text
START → hydrate → classify → refuse|offer|confirm|agent ⇄ tools → finalize → persist → END
```

| 节点 | 职责 |
|------|------|
| hydrate | 拉 Java 会话上下文 / turns 回填 / profile+memory+offer；无合法 phase → lobby |
| classify | 规则意图 → phase → route / close_scope；低置信或注入 → confirm |
| refuse / offer | 确定性话术 |
| confirm | 灰区选项；前端点选后固定文案入对话 |
| agent ⇄ tools | 标准图边 tool loop；工具失败仍返回 ToolMessage |
| finalize | 护栏 + SSE（不二次调 LLM）+ summary 窗口 |
| persist | **每回合** append turns + sync_session_state；收束时可 remember |

`close_scope`：`none` | `problem_segment`（收束本题，会话仍 active）| `session`（关线）

原则补充：**图成功与 L2 落库同属 persist 路径**，不在 SSE `done` 后图外写库。

## 会话字段（L2）

`coach_sessions` 含：`topic`、`session_kind`（`lobby|problem|topic`）、`summary`。

- 开专题：`POST /api/coach/prepare` body `{ "topic": "链表" }` → 复用同用户同 topic 的 active 会话
- 换专题：新 prepare（新 session_id），不要在同一 checkpoint 里改 topic

## 原则

1. Python **不**直连业务库写记忆
2. 所有表带 `user_id`，工具强制当前用户过滤
3. Checkpoint 空时由 hydrate 从 `coach_turns` 复活
4. 不做向量 RAG（结构化记忆稳定后再议）
