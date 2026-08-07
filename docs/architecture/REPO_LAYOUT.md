# 仓库布局与 Polyglot Monorepo

## 结论

**把 Java 微服务与 Python LLM 服务放在同一个仓库是合理的**（polyglot monorepo）。  
原则：**同仓协作，分生命周期构建与部署；服务之间只走网络契约，不互相 import 源码树。**

参考：

- [create-polyglot](https://github.com/kaifcoder/create-polyglot)：`apps/` + `infra/` + 根级 compose
- [polyglot-prime](https://github.com/tech-by-design/polyglot-prime)：顶层按独立可构建单元划分

---

## 服务变多会不会乱？

会乱的通常不是「Java 和 Python 同仓」，而是两件事叠在一起：

1. **目录**：十几个服务平铺在 `apps/`，找不到归属  
2. **启动**：本地每次要手动起一长串进程

对策：**按业务域命名与分组 + 按 profile 裁剪启动**；并且 **不要过早拆微服务**（组队、支付没上线前，继续放在现有业务服务里用模块/包隔离即可）。

### 现阶段（推荐保持扁平）

当前只有 4 个可部署单元，扁平完全够用：

```
apps/
  gateway/              # 唯一入口 · 路由 / 鉴权 / 限流
  business-service/     # 刷题业务（题目、提交、统计、学习偏好…）
  llm-service/          # 陪练 · Python
  web/                  # 前端
```

### 服务增多后的目标形态（按域，不按语言）

**不要**做成 `apps/java/*` + `apps/python/*`（语言墙会拆散同一域的协作）。  
**要**按限界上下文（业务域）命名，需要时再加一层域目录：

```
apps/
  gateway/                    # 平台入口（始终一个）

  # --- 学习域（刷题核心）---
  problem-service/            # 题库、题单（从 business 拆出时）
  submission-service/         # 提交、判题回调（若独立）
  llm-service/                # 陪练（Python，仍挂在学习域语义下）

  # --- 用户 / 社交域 ---
  user-service/               # 账号、资料、鉴权相关 API
  team-service/               # 组队刷题、房间、邀请

  # --- 商业域 ---
  payment-service/            # 支付、订单、会员

  web/                        # 前端仍一个（或以后 BFF，仍别按语言拆）
```

若目录真的超过 ~8 个服务，再升一级目录（可选）：

```
apps/
  platform/gateway/
  learning/problem-service/
  learning/llm-service/
  social/team-service/
  commerce/payment-service/
  web/
```

IDE / CI 按路径过滤即可；**每个叶子目录仍是独立可构建、可部署单元**（自有 `pom.xml` 或 `pyproject.toml`）。

### 命名约定

| 规则 | 例子 |
|------|------|
| `{域}-service` | `team-service`、`payment-service` |
| 网关不加业务后缀语义 | `gateway` |
| 前端不加 `-service` | `web` |
| 禁止 `common-service` 大泥球 | 共享代码进 `packages/`，不是又一个 HTTP 服务 |

### 拆分时机（避免「微服务焦虑」）

| 先做 | 后做 |
|------|------|
| 在 `business-service` 里用 Java package 分模块：`user` / `team` / `billing` | 独立进程 + 独立库表 |
| 表仍在同一 Postgres，schema 或表前缀分区 | 支付等高安全域再独立库 |
| Gateway 路由先按 path 前缀预留 `/api/team/**` | 真有流量/团队边界再拆服务 |

**经验阈值**：同一域代码能被一个小团队在一个服务里改完，就先别拆；出现独立发布节奏、独立扩缩容、或强隔离（支付）再拆。

---

## 启动不会乱：编排按 profile

本地**默认只起核心路径**，其它域按需：

```bash
# 构想（后续 Makefile / compose 可落地）
make infra-up          # postgres redis nacos
make run-core          # gateway + business + llm + web
make run-social        # + user + team（开发组队时）
make run-commerce      # + payment（开发支付时）
```

Docker Compose 用 **profile**，而不是每次 `up` 全开：

```yaml
# 示意
services:
  team-service:
    profiles: ["social", "full"]
  payment-service:
    profiles: ["commerce", "full"]
```

| Profile | 起哪些 |
|---------|--------|
| （默认 / core） | gateway、business、llm、web、infra |
| `social` | + user、team |
| `commerce` | + payment |
| `observability` | 监控（已有） |
| `full` | 全部 |

开发者浏览器始终只打 **Gateway :8080**（或 Nginx :80），不必记十几个业务端口。

Java 侧可选：根目录加一个 **Maven Reactor 父 POM**（只聚合 `apps/*-service` 的 Java 模块），方便 `mvn -pl team-service -am spring-boot:run`；**不是**把所有服务编成一个 jar。

---

## 当前布局

```
CodeArena/
├── apps/
│   ├── gateway/
│   ├── business-service/
│   ├── llm-service/
│   └── web/
├── deploy/
├── docs/architecture/
├── scripts/
├── docker-compose.yml
├── docker-compose.infra.yml
└── Makefile
```

可选（有真实共享代码时再加）：

```
packages/
  java-common/       # 错误码、鉴权注解等（慎用，防变成大泥球）
  api-contracts/     # OpenAPI / proto（契约，不是运行时硬依赖）
```

## 边界

| 允许 | 禁止 |
|------|------|
| 同 PR 改 gateway 路由 + 某域服务 + web | 服务间互相 Maven/pip 依赖对方业务模块 |
| 共享 Postgres/Redis 的**数据契约** | 为「整齐」按语言建 `apps/java`、`apps/python` |
| compose profile / make 目标裁剪启动 | 本地默认拉起全部微服务 |
| 先在 business 内按包拆域 | 尚未有需求就建空的 `payment-service` |

每个 `apps/*` 自有构建清单、Dockerfile、独立进程与镜像。

业务进程内的域分包与 Gateway 路径预留见 [MODULAR_BUSINESS.md](./MODULAR_BUSINESS.md)。  
用户域与旧表演进见 [USER_DOMAIN.md](./USER_DOMAIN.md)。
