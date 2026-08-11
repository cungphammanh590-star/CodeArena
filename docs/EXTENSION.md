# 浏览器扩展（用户侧）

扩展是力扣页上的薄客户端。用户只需 **在扩展中注册/登录账号**；服务地址由产品内置，界面不暴露。

```text
leetcode.cn → 扩展 → Gateway（内置 API）→ Java /（stream）Python
```

**重要：** 力扣提交不会自动进库，必须由扩展在 **已登录** 状态下调用 `POST /submit`。  
只登录 Web 仪表盘、扩展未登录 → 提交同步会失败（弹窗会显示「未登录」或角标 `!`）。

## 用户可见能力

- 账号登录 / 注册 / 退出（选项页「账号」）
- 弹窗：状态、陪练建议、打开仪表盘/陪练
- 提交自动同步到当前登录账号；若未登录会暂存，登录后自动补传

## 鉴权与 Web 同步

- 鉴权：`POST /api/auth/login|register`，请求头 `Authorization: Bearer <JWT>`
- Web（`5173`）登录后，`web-bridge` 会把页面 `localStorage` 中的 JWT 写入扩展
- 扩展登录成功也会用 `?ext_token=` 打开 Web，实现双向对齐

## 开发者注意

发版前在 `extension/config.js` 修改内置常量：

- `API_BASE`：Gateway 公网地址  
- `WEB_BASE`：Web 仪表盘地址  

本地默认分别为 `http://127.0.0.1:8080` 与 `http://127.0.0.1:5173`。

设备静默登录接口仍保留给内部/兼容，**不对用户展示**。

## 加载扩展

Chrome → 扩展程序 → 加载已解压的扩展 → 选 `extension/` → 打开「账号登录」注册后使用。  
更新代码后请点「重新加载」，再刷新已打开的 `leetcode.cn` 标签页。

## 排查「力扣交了但陪练没有提交」

1. 扩展弹窗「状态」是否为 **已就绪 / 已登录**（不是「未登录」或「不可用」）
2. Gateway `:8080` 与 business `:8090` 是否在跑
3. 是否在 **leetcode.cn**（非 leetcode.com）题目页提交
4. 看弹窗「最近一次」：失败原因会写在这里；未登录会提示暂存
5. 登录后可再交一次，或依赖自动补传队列
6. 若控制台出现 `extension context invalidated` / `chrome.runtime` undefined：说明扩展刚被「重新加载」，**必须刷新力扣页面**后再提交
