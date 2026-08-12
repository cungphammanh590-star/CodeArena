# 数据与缓存

## Postgres 真相表

| 域 | 表 |
|----|-----|
| 用户 | `users`、`user_profiles`、`user_identities`、`user_credentials`、`auth_sessions`、`user_llm_settings` |
| 刷题 | `problems`、`submissions` |
| 学习 | `problem_lists`、`problem_list_items`、`learning_prefs`、`user_problem_flags`、`study_plans`、`plan_daily_tasks`、`goal_problem_banks`、`user_problem_srs` |
| Nex | `coach_sessions`、`coach_turns`、`user_coach_memories` |
| 用量 | `llm_usage_events` |

V5 已删除无用占位：`team_*`、`pay_orders`、`plan_notifications`、`knowledge_points`、`user_kp_mastery`、`coach_code_runs`、空聚合表 `problem_stats` / `problem_daily_stats`。

V7：`user_problem_srs`（题级 SM-2：ease / interval_days / reps / due_at / suspended）。

### 关键索引（V5 / V7）

- `submissions (user_id, submitted_at DESC)`
- `submissions (user_id, problem_id) WHERE status = 'Accepted'`
- `submissions (user_id, problem_id, submitted_at DESC)`
- `user_problem_flags (user_id, problem_id) WHERE mastered`
- `user_problem_srs (user_id, due_at) WHERE NOT suspended`

## 间隔复习（SRS）

```text
首次 AC → 建卡，due = 明天（GOOD）
再次 AC → SM-2 推进 interval / ease
非 AC（已有卡）→ AGAIN，间隔重置为 1 天
标记掌握 → suspended=true（不进今日复习）
取消掌握 → suspended=false
历史 AC 首次拉 /review/today 时 backfill 建卡（due ≈ 上次 AC+1 天；已逾期则今日到期，上限 200）
```

`/api/review/today`：`plan_items`（计划）+ `review_items`/`due`（SRS）；`queue` 为合并去重列表。

## Redis

| 用途 | 说明 |
|------|------|
| LangGraph Checkpoint | llm-service；`thread_id=smart:{user}:{session}` |
| 用户维 stats | business `UserStatsCacheService`：`stats:u:{id}:d:{date}`、`…:portrait` |
| Gateway 限流 | 仍进程内 |

开关：`codearena.cache.redis-enabled`（local 默认 false）、`stats-enabled`、`stats-ttl-seconds`。

## 一致性

```text
读：Cache-Aside（hit 返回 / miss 算完写入）
写：Submit、Mastery、SRS 更新成功 → invalidateUser
Redis 挂了：静默直查 DB
```

前端：进页加载 + 手动刷新。不维护第二套 PG 聚合写路径。
