# CodeArena LLM Service

FastAPI **对话**服务，端口 **8091**。

职责边界（见 `docs/architecture/BUSINESS_FLOW.md`、`COACH_TOOLS.md`）：

- **只做**：`POST /api/coach/stream`（SSE + LangGraph）
- **不做**：用户、提交、题单、prepare/session/hint、LLM Key 持久化（均在 **business-service**）
- **工具**：回调 Java `POST /internal/tools/exec`
- **模型 Key**：回调 Java `GET /internal/users/llm`

## 本地运行

```bash
cd apps/llm-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8091 --reload
```

或仓库根目录：`make llm`

## 端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/health` | 进程健康 |
| GET | `/metrics` | Prometheus |
| POST | `/api/coach/stream` | Nex SSE（经 Gateway） |

环境变量：`BUSINESS_INTERNAL_URL`、`INTERNAL_TOOL_TOKEN`、`REDIS_URL`、`CHECKPOINT_BACKEND`。  
图与记忆：`docs/architecture/COACH_LANGGRAPH.md`。
