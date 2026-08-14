# 短路线

**主轴已通**：扩展提交 → 统计/题单 → LangGraph Nex（含 P0 sandbox）→ **题级 SRS**。

## 已收敛（勿回潮）

- 无 team/pay 空 API；无空 `problem_stats` 双轨；Dashboard **手动刷新**（非盲轮询）
- 业务写库只在 Java；生产勿映射 business/llm 宿主机端口
- 可观测性 / Langfuse / Nacos 保持 opt-in
- 今日待办分列：**计划排期**（`study_plan`）与 **间隔复习**（`user_problem_srs`），勿再混标
- Dashboard 与学习档案只在进页或用户点击刷新时读取；禁止为“同步状态”增加定时轮询、独立状态表或 SSE，避免无收益的常驻请求放大高并发压力

## 下一步（按需）

| 优先级 | 项 |
|--------|-----|
| 高 | 用户知识库精炼 + 闪卡 SRS（OpenSpec `kb-llm-refine-flashcard-srs`）：规则/可选 LLM 精炼 → 闪卡掌握 → 画像 |
| 高 | 扩展同步成功后通知 Web（减少点刷新） |
| 高 | 生产强密钥 + Gateway 限流迁 Redis（多副本） |
| 中 | 沙箱加固（bwrap/sidecar）；`append_code_run` 审计 |
| 低 | OAuth；知识库 Web 精装 / 换 embedding 重建任务 |
| 低 | SRS 手动评级（Again/Hard/Good/Easy UI）；按难度调初始间隔 |

知识库设计：[KNOWLEDGE_BASE.md](./KNOWLEDGE_BASE.md)。

数据面细节：[DATA_CACHE.md](./DATA_CACHE.md)。架构入口：[OVERVIEW.md](./OVERVIEW.md)。
