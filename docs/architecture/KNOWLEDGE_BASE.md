# 用户知识库（Knowledge Base）

用户私有学习资产：Document → KnowledgePoint → EmbeddingRef。  
**PostgreSQL 为真相源**；**Qdrant 为可重建向量索引**。Nex 经 Coach Tool 检索并引用。

详细变更：[openspec/changes/user-knowledge-base-rag/](../../openspec/changes/user-knowledge-base-rag/)。

## 原则

1. 写库与向量 upsert/delete/search 归属 **business-service** Knowledge Domain。  
2. Python **不**直连 PG / Qdrant 写路径；只调 `/internal/tools/exec`。  
3. 与 L3 `user_coach_memories`（偏好/事实）分立：KB 是可复习内容资产。  
4. 强制 `user_id` 隔离；单 collection + payload filter。

## 领域模型

| 对象 | 表 | 说明 |
|------|-----|------|
| Document | `kb_documents` | 用户投递材料；`source_type=text\|markdown\|pdf`；`raw_text` / `cleaned_text` |
| KnowledgePoint | `kb_knowledge_points` | 标题+正文+topic+tags |
| EmbeddingRef | `kb_embeddings` | model/version + `qdrant_point_id` |

## Ingest 状态机

```text
uploaded → parsing → cleaning → extracting → embedding → ready
                ↘ failed（任一步；可 reprocess）
```

- **parsing**：txt/md 透传；PDF 抽文本层（无有效文本 → failed，不做 OCR）。  
- **cleaning**：L1 规则去引流/页眉页脚；可选 L2 LLM scrub。  
- **extracting**：规则/LLM 产出 KP（禁止「只有匿名 chunk」作为唯一成功形态）。  
- **embedding**：写 `kb_embeddings` + Qdrant upsert。

## API（Gateway → Java）

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/knowledge/documents` | 创建（JSON 正文或 multipart PDF） |
| GET | `/api/knowledge/documents` | 列表 |
| GET | `/api/knowledge/documents/{id}` | 详情 |
| DELETE | `/api/knowledge/documents/{id}` | 级联删 KP + 向量 |
| POST | `/api/knowledge/documents/{id}/reprocess` | 重跑摄入 |
| GET | `/api/knowledge/kps` | 知识点列表 |
| GET | `/api/knowledge/kps/{id}` | 详情 |
| DELETE | `/api/knowledge/kps/{id}` | 删 KP + 向量 |

开关：`codearena.knowledge.enabled`（env `CODEARENA_KNOWLEDGE_ENABLED`）。

## Coach 工具

| 工具 | 类型 | 说明 |
|------|------|------|
| `search_user_knowledge` | READ | 语义检索；强制当前用户；返回来源字段 |
| `get_knowledge_point` | READ | 按 id 读详情 |
| `get_kp_review_due` | READ | 知识点闪卡今日到期 |

## Qdrant

- Collection：`CODEARENA_QDRANT_COLLECTION`（默认 `leetmate_user_kb`）  
- Payload：`user_id`, `kp_id`, `doc_id`, `topic`, `version`  
- Embedding：`CODEARENA_EMBEDDING_PROVIDER=mock|http`；mock 用特征哈希便于本地闭环  
- 火山 Ark：文本 embedding 需开通后走 `/api/v3/embeddings`；当前可用 `doubao-embedding-vision-*` 走 `/api/v3/embeddings/multimodal`（dim=2048）。换维度会自动重建 Qdrant collection，旧文档需 `reprocess`。  
- 抽取：优先识别独立问句行（以？结尾的题干）、`问：`/`面试题：`、编号题；否则 Markdown 标题 / 段落窗口。单文档上限 `CODEARENA_KNOWLEDGE_MAX_KPS`（默认 200）。
- **精炼**：规则去掉训练营/番外尾巴并填 `question`/`answer`；`CODEARENA_KNOWLEDGE_LLM_REFINE=true` 时可走 Chat JSON（失败回退规则）。
- **闪卡**：`user_kp_srs`（SM-2）；API `/api/knowledge/flashcards/due` + `.../review`；Coach 工具 `get_kp_review_due`。

## 非目标（本阶段）

OCR / 扫描 PDF、知识图谱、跨用户共享、Kafka、全对话自动抽 KP、DeepTutor 多 RAG 引擎。

相关：工具清单 [COACH_TOOLS.md](./COACH_TOOLS.md)；数据表 [DATA_CACHE.md](./DATA_CACHE.md)。
