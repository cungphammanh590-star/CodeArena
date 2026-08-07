# 浏览器扩展（用户侧）

扩展是力扣页上的薄客户端。用户只需 **注册/登录账号**；服务地址由产品内置，界面不暴露。

```text
leetcode.cn → 扩展 → Gateway（内置 API）→ Java /（stream）Python
```

## 用户可见能力

- 账号登录 / 注册 / 退出（选项页「账号」）
- 弹窗：状态、陪练建议、打开仪表盘/陪练
- 提交自动同步到当前登录账号

## 开发者注意

发版前在 `extension/config.js` 修改内置常量：

- `API_BASE`：Gateway 公网地址  
- `WEB_BASE`：Web 仪表盘地址  

本地默认分别为 `http://127.0.0.1:8080` 与 `http://127.0.0.1:5173`。

鉴权：`POST /api/auth/login|register`，Bearer `ca_…`。设备静默登录接口仍保留给内部/兼容，**不对用户展示**。

## 加载扩展

Chrome → 扩展程序 → 加载已解压的扩展 → 选 `extension/` → 打开「账号登录」注册后使用。
