# CodeArena

在线刷题平台。  
技术栈：**Vue 3** + **Spring Boot 3 / Gateway** + **FastAPI LLM 陪练**，配套 Postgres / Redis / Nacos / Nginx / 可观测性。

## 仓库结构

```
apps/                  # 产品运行单元（各自独立构建）
  web/                 Vue 3 + Vite
  gateway/             Spring Cloud Gateway :8080
  business-service/    Spring Boot :8090
  llm-service/         FastAPI :8091
deploy/                基础设施与发布（Nginx / 监控 / Helm / …）
docs/architecture/     架构说明
scripts/               开发辅助脚本
docker-compose.yml     全栈编排
docker-compose.infra.yml  仅基础设施
```

布局原则见 [`docs/architecture/REPO_LAYOUT.md`](docs/architecture/REPO_LAYOUT.md)。  
业务边界见 [`docs/architecture/BUSINESS_FLOW.md`](docs/architecture/BUSINESS_FLOW.md)（**业务进 Java，对话仅 Python**）。

## 端口

| 服务 | 端口 |
|------|------|
| Nginx | 80 |
| Vue Dev / Web | 5173 |
| Gateway | 8080 |
| Business | 8090 |
| LLM | 8091 |
| Postgres | 5432 |
| Redis | 6379 |
| Nacos | 8848 |
| Prometheus / Grafana / Loki / SkyWalking | 9090 / 3000 / 3100 / 8088（profile `observability`） |

## 前置依赖

- **JDK 21**、**Maven 3.9+**（本机构建 Java）
- **Node.js 20+**（前端）
- **Python 3.11+**（LLM 服务）
- **Docker Desktop**（推荐，用于基础设施与全栈）

## 本地开发（推荐）

```bash
# 1) 基础设施
cp .env.example .env
make infra-up          # postgres + redis + nacos

# 2) 业务 / 网关 / LLM（三个终端）
make business          # :8090
make gateway           # :8080
make llm               # :8091

# 无 Postgres 时可用 H2 冒烟：
# cd apps/business-service && mvn spring-boot:run -Dspring-boot.run.profiles=local

# 3) 前端
make web-dev           # http://localhost:5173  （代理 /api → :8080）
```

## Docker 全栈

```bash
cp .env.example .env
make up
# 浏览器打开 http://localhost

docker compose --profile observability up -d   # 可观测性（较重）
```

## Vue 页面

| 路由 | 说明 |
|------|------|
| `/` | 仪表盘 |
| `/problems/:id` | 题目详情 |
| `/coach` | AI 陪练（SSE） |
| `/ops` | 维护台 |

浏览器扩展见 [`docs/EXTENSION.md`](docs/EXTENSION.md)（`extension/`，经 Gateway，支持设备/账号登录）。

## 更多

- 架构总览：[`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md)
- 仓库布局：[`docs/architecture/REPO_LAYOUT.md`](docs/architecture/REPO_LAYOUT.md)
