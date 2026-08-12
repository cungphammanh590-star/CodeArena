---
name: codearena-web-ux
description: >-
  Improve CodeArena Vue web UX for Nex coach, dashboard, problem, login,
  and ops. Use when editing apps/web, redesigning UI, polishing Chinese copy,
  or when the user asks to make the frontend clearer, prettier, or more usable.
paths: apps/web/**
---

# CodeArena Web UX

**刷题 + Nex（AI 陪练）**。栈：Vue 3 + Vite + Pinia + Router（`apps/web/`）。

## 页面优先级

1. Nex `/coach` — 对话清晰 > 装饰
2. Problem `/problems/:id` — 状态/最近提交可扫读
3. Dashboard `/` — 一眼进度 + **一个**进 Nex CTA
4. Login — 短、稳
5. Ops — 可密，但同 token

## 代码锚点

| 用途 | 路径 |
|------|------|
| Token | `src/assets/theme.css` |
| 导航 | `AppHeader.vue`, `AuthBar.vue` |
| Nex | `views/CoachView.vue`, `stores/coach.ts` |
| 用户可见错误 | `utils/userMessage.ts` |

小改动沿用现有 CSS 变量与 class；大改可动 token。

## 视觉（内化原则）

- **8pt 间距**：8 / 16 / 24 / 32 / 48；相关更近、区块更远
- **一层主操作**：每屏一个 primary；次要用 secondary / text
- **对比**：正文对比 ≥ 4.5:1；可点区域约 ≥ 44–48px
- **字体**：保留 IBM Plex Sans + Noto Sans SC；字重通常 2 档够用
- **少装饰**：少边框、少阴影叠层、不用 emoji 当图标、不用紫色渐变/glow/胶囊统计条
- **当前主题**偏 cream + blush + soft purple；大改时收成「中性底 + 一个强调色」，小修先别换皮

## Nex（最高杠杆）

- 开场口语化，禁「会话已就绪 / 模式：xxx」
- 用户 / Nex 气泡区分清楚；Markdown 可读；流式少抖布局
- Composer：主按钮明确；Enter 发送；禁用要说人话
- 确认 / ask-user：选项用按钮，别埋文案里
- 错误只走 `userMessage`；busy 反馈贴近输入区

## Dashboard / Problem

- 首屏最多约 3 个指标 + 一个 CTA（打开 Nex / 继续某题）
- 题页：题名、状态、最近提交在前；深统计靠后

## 文案（zh-CN）

- 短句；按钮「动词 + 对象」（发送 / 结束本轮 / 提交回答）
- Nex 语气（你/我），不要系统公告腔
- 产品名：平台 **CodeArena**，陪练 **Nex**
- 空状态：一句话说下一步

## 组件习惯（够用即可）

- 表单：单列，label 在上；placeholder 不当 label
- 导航：当前位置清晰；移动端别挤死
- 反馈：loading / empty / error 三态都要有下一动作
- 列表/消息流：固定对齐列，操作区宽度稳定

## 工作流

1. 先定本屏用户任务，再删不服务任务的 chrome  
2. 动布局/样式前读现有 `theme.css` 与同页 scoped 样式  
3. 共享 token 进 `theme.css`；一次性布局留在页面内  
4. 用 ~375 与桌面各看一眼，Nex 两端都要能用  

## 不做

- 未要求时不改 `extension/`
- 不改后端 / LangGraph 逻辑（只动 UI 与文案）
- 不借「好看」加新功能
