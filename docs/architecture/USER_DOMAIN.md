# 用户与鉴权

## 当前用户解析（business）

`CurrentUserService.require`：

1. `Authorization: Bearer <JWT>` → 验签 + `auth_sessions` 未吊销  
2. 仅当 `X-CodeArena-Gateway-Auth=jwt` + `X-User-Public-Id` → 信任 Gateway 头  
3. 否则忽略客户端伪造的用户头 → 种子用户 `default`（本机直连开发）

Gateway `JwtAuthGlobalFilter` 对非公开路径校验 JWT，并注入上述可信头。  
公开：`/health`、`/api/auth/login|register|device`、部分 actuator。

## 鉴权 API

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/auth/device` | 设备静默登录（扩展兼容） |
| POST | `/api/auth/register` / `login` | 注册 / 登录，返回 JWT |
| POST | `/api/auth/logout` | 吊销 jti |
| GET | `/api/auth/me` | 当前用户（必须 JWT） |

密钥：`CODEARENA_JWT_SECRET`（Gateway 与 business 相同）。扩展见 [EXTENSION.md](../EXTENSION.md)。

## 用户 API

| Method | Path | 说明 |
|--------|------|------|
| GET/PATCH | `/api/users/me` | 当前用户 / 改资料 |
| GET | `/api/users/{publicId}` | 按对外 ID |
| GET/POST | `/api/users/me/llm/**` | 个人 LLM 配置 |

对外用 `public_id`；库内 FK 用 `users.id`。跨域用 `UserLookup`，勿直接注入 `UserService`。

## 核心表

`users`、`user_profiles`、`user_identities`、`user_credentials`、`auth_sessions`、`user_llm_settings`。
