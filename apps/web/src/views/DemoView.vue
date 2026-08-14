<script setup lang="ts">
import { computed, ref } from "vue";

const tabs = ["今日任务", "Nex 陪练", "知识复习"] as const;
type Tab = (typeof tabs)[number];
const tab = ref<Tab>("今日任务");
const content = computed(() => ({
  "今日任务": { title: "双指针：找出边界", text: "从一次错误提交开始。先判断什么情况下左指针应该移动。", status: "15 分钟 · 待开始", action: "开始本题" },
  "Nex 陪练": { title: "先说出你的判断", text: "Nex 不直接贴答案，会根据你的代码和思路追问到关键条件。", status: "提示等级 1 / 3", action: "继续思考" },
  "知识复习": { title: "滑动窗口的收缩条件", text: "翻开闪卡，回忆窗口何时收缩；答完后按掌握程度安排下一次复习。", status: "2 张卡片到期", action: "翻开卡片" },
}[tab.value]));
</script>

<template>
  <main class="demo-page">
    <header class="demo-nav">
      <RouterLink class="brand" to="/">CodeArena</RouterLink>
      <RouterLink class="btn-secondary" to="/login?mode=register">创建自己的空间</RouterLink>
    </header>
    <section class="demo-intro">
      <p class="eyebrow">无需登录的产品演示</p>
      <h1>学习不是完成一次任务，而是留下下一次能继续使用的线索。</h1>
      <p>下面是一个静态学习空间：它不会读取或保存任何数据。</p>
    </section>
    <section class="demo-workspace">
      <aside>
        <p>学习路径</p>
        <button v-for="item in tabs" :key="item" type="button" :class="{ active: tab === item }" @click="tab = item">{{ item }}</button>
      </aside>
      <article>
        <p class="eyebrow">{{ tab }}</p>
        <h2>{{ content.title }}</h2>
        <p class="copy">{{ content.text }}</p>
        <p class="status">{{ content.status }}</p>
        <button type="button" class="btn-primary">{{ content.action }}</button>
      </article>
      <div class="demo-side">
        <span>本周学习</span>
        <strong>4</strong>
        <p>次专注练习</p>
        <hr />
        <span>待复习</span>
        <strong>3</strong>
        <p>个知识点</p>
      </div>
    </section>
    <section class="demo-next">
      <div><h2>这只是演示。</h2><p>登录后，这些任务会由你的代码、笔记和学习历史生成。</p></div>
      <RouterLink class="btn-primary" to="/login?mode=register">开始建立学习档案</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.demo-page { min-height: 100vh; padding: 0 24px 64px; background: var(--bg-elevated); }
.demo-nav, .demo-intro, .demo-workspace, .demo-next { max-width: 1000px; margin-left: auto; margin-right: auto; }
.demo-nav { min-height: 72px; display: flex; align-items: center; justify-content: space-between; }
.brand { color: var(--ink); text-decoration: none; font-size: 19px; font-weight: 700; letter-spacing: -.04em; }
.demo-nav :deep(.btn-secondary), .demo-next :deep(.btn-primary) { display: inline-flex; align-items: center; justify-content: center; text-decoration: none; }
.demo-intro { padding: 48px 0 40px; max-width: 760px; }
.eyebrow { color: var(--accent); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.demo-intro h1 { margin: 8px 0 16px; font-size: clamp(34px, 5vw, 54px); line-height: 1.1; letter-spacing: -.05em; }
.demo-intro > p:last-child, .copy, .demo-next p { color: var(--muted); line-height: 1.65; }
.demo-workspace { display: grid; grid-template-columns: 190px minmax(0, 1fr) 190px; border: 1px solid var(--line); border-radius: 16px; overflow: hidden; background: var(--card); box-shadow: var(--shadow); }
aside { padding: 18px; background: var(--soft); border-right: 1px solid var(--line); } aside p { margin: 0 0 14px; color: var(--muted); font-size: 12px; } aside button { display: block; width: 100%; min-height: 42px; margin: 4px 0; border: 0; border-radius: 8px; background: transparent; color: var(--muted); text-align: left; font: inherit; font-size: 14px; cursor: pointer; } aside button.active { padding: 0 10px; background: var(--accent-soft); color: var(--accent); font-weight: 650; }
article { padding: 40px; } article h2 { margin: 8px 0 16px; font-size: 30px; letter-spacing: -.04em; } .copy { min-height: 82px; font-size: 16px; } .status { margin: 32px 0 12px; color: var(--muted); font-size: 13px; }
.demo-side { padding: 28px; border-left: 1px solid var(--line); } .demo-side span { display: block; color: var(--muted); font-size: 13px; } .demo-side strong { display: block; margin-top: 4px; font-size: 38px; letter-spacing: -.05em; } .demo-side p { margin: 0; color: var(--muted); font-size: 13px; } .demo-side hr { border: 0; border-top: 1px solid var(--line); margin: 28px 0; }
.demo-next { margin-top: 24px; padding: 28px; display: flex; align-items: center; justify-content: space-between; gap: 24px; border: 1px solid var(--line); border-radius: var(--radius); } .demo-next h2 { margin: 0; font-size: 22px; } .demo-next p { margin: 6px 0 0; }
@media (max-width: 760px) { .demo-page { padding: 0 16px 48px; } .demo-workspace { grid-template-columns: 1fr; } aside { display: flex; gap: 6px; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--line); } aside p { display: none; } aside button { flex: 0 0 auto; width: auto; padding: 0 10px; } .demo-side { display: flex; align-items: baseline; gap: 8px; border-left: 0; border-top: 1px solid var(--line); padding: 18px; } .demo-side strong { margin: 0; font-size: 25px; } .demo-side hr, .demo-side p { display: none; } article { padding: 28px 20px; } .demo-next { display: block; } .demo-next :deep(.btn-primary) { margin-top: 16px; } }
</style>
