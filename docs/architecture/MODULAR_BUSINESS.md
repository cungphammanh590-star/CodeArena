# 业务侧模块化单体（Modular Monolith）

当前形态：**一个 `business-service` 进程，按业务域分包**；Gateway 已按域路径拆路由。  
目标：扩展组队/支付时**不停整站逻辑**（滚动发版即可），将来拆进程只改 Gateway URI + 搬包。

## 包结构原则

1. **一级目录只按业务域**，禁止顶层 `controller` / `entity` / `repository`。
2. **域内**统一：`domain`（Entity/Repo）· `service`（用例）· `web`（HTTP）。
3. **`shared` 铁律**：只放真正横切、与任何业务域无关的东西（如 `InternalTokenGuard`、Redis/Nacos、探活）。  
   与某域相关的配置/审计必须下沉到该域；禁止把 `shared` 当垃圾回收站。
4. **跨域依赖**：优先注入对方 `api` 门面（如 `user.api.UserLookup`），不要直接依赖对方 `*Service` 实现细节。  
   **规划**：将 `*-api` 拆成独立 Maven 子模块（仅接口 + DTO），用编译期隔离防止门面形同虚设。
5. **对外 vs 内网 API**：有内网回调的域在 `web` 下分 `external`（`/api/**`）与 `internal`（`/internal/**`）。

## 目标树

```
com.codearena.business/
├── shared/                 # 仅真横切
│   ├── config/             # Redis、Nacos
│   ├── security/           # InternalTokenGuard
│   └── web/                # HealthController（只碰 DataSource）
├── user/
│   ├── api/                # UserLookup（跨域门面；后续 → user-api 模块）
│   ├── domain/
│   ├── service/
│   └── web/{external,internal}/
├── problem/                # 题库 + 题目统计
│   ├── domain/
│   └── web/
├── submission/
│   ├── domain/
│   └── web/
├── learning/               # 内部再分子域，避免巨石
│   ├── preference/         # /api/learning
│   ├── list/               # /api/lists/**
│   ├── mastery/            # /api/mastered
│   └── plan/               # /api/review/**
├── coach/
│   ├── tool/ + tool/impl/  # 策略契约与实现
│   └── web/{external,internal}/   # /api/coach/** vs /internal/tools/**
├── ops/                    # /api/ops/**
├── team/ · pay/            # 桩；web/external
└── BusinessServiceApplication.java
```

## 域一览

| 域包 | HTTP 前缀 | 表 / 说明 | 状态 |
|------|-----------|-----------|------|
| `user` | `/api/users/**` · `/internal/users/**` | `users` + `user_*` | 已实现；见 [USER_DOMAIN.md](./USER_DOMAIN.md) |
| `problem` | `/api/problems/**` · `/api/stats/**` | `problems` · `problem_stats*` | 已迁入 |
| `submission` | `/submit` | `submissions` | 已迁入 |
| `learning.*` | learning / lists / mastered / review | prefs · lists · flags | 已迁入并分子域 |
| `coach` | `/api/coach/**` · `/internal/tools/**` | 会话编排 + 记忆；stream 在 llm-service | 见 [COACH_TOOLS.md](./COACH_TOOLS.md)、[COACH_MEMORY.md](./COACH_MEMORY.md) |
| `ops` | `/api/ops/**` | 运维台 | 已迁入 |
| `team` | `/api/team/**` | `team_*` | 桩 |
| `pay` | `/api/pay/**` | `pay_*` | 桩 |
| `shared` | `/health` | 无业务表 | 平台横切 |

## Gateway

`application.yml` / `application-nacos.yml` 中 **域路由写在 `/api/**` catch-all 之前**：

- `domain-team` → 今 `business-service`，明后可改 `team-service`
- `domain-pay` → 同上 → `payment-service`
- `domain-users` → 同上 → `user-service`

拆分步骤（有需要时）：

1. 新建 `apps/team-service`，搬 `team` 包与 `team_*` 迁移归属  
2. Gateway `domain-team` 的 `uri` 改为新服务  
3. 滚动发布：先起新服务，再切路由，再缩旧实例 —— **无需「整站停机维护窗口」**

## 后续：Maven `*-api` 编译期隔离（规划）

当前同 jar 内用 `user.api.UserLookup` 约定跨域边界。业务变复杂后：

1. 抽出 `packages/user-api`（或 `apps/business-service` 多模块 reactor）  
2. 仅含接口 + 只读 DTO；`user` 实现模块依赖 api  
3. `learning` / `coach` **只**依赖 `user-api` jar，看不见 `UserService`

## 本地探活

```bash
curl -s http://127.0.0.1:8090/health
curl -s http://127.0.0.1:8090/api/team/health
curl -s http://127.0.0.1:8090/api/pay/health
curl -s http://127.0.0.1:8090/api/users/health
```

## 与「微服务」关系

这是 **模块化单体 + 可拆路由**，不是微服务爆炸。详见 [REPO_LAYOUT.md](./REPO_LAYOUT.md)。
